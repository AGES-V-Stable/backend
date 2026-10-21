package ages.vstable.backend.service;

import ages.vstable.backend.dto.authentication.LoginResponseDTO;
import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwoFactorAuthServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-16T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdministratorRepository administratorRepository;

    @Mock
    private JwtTokenUtils jwtTokenUtils;

    @Mock
    private UserService userService;

    private TotpService totpService;
    private TwoFactorAuthService twoFactorAuthService;

    @BeforeEach
    void setUp() {
        totpService = new TotpService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        twoFactorAuthService = new TwoFactorAuthService(
                totpService, userRepository, administratorRepository, jwtTokenUtils, userService);
    }

    @Test
    void setupTwoFactor_deveSalvarSegredoTemporarioComTwoFactorEnabledFalse() {
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorEnabled(false)
                .build();

        TwoFactorSetupResponseDTO response = twoFactorAuthService.setupTwoFactor(user);

        assertThat(response.isTwoFactorEnabled()).isFalse();
        assertThat(response.getOtpauthUri()).startsWith("otpauth://totp/V-Stable:user%40vstable.com?secret=");
        assertThat(user.getTwoFactorSecret()).isNotBlank();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void setupTwoFactor_quando2faJaAtivo_deveBloquearComConflictException() {
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                .twoFactorEnabled(true)
                .build();

        assertThatThrownBy(() -> twoFactorAuthService.setupTwoFactor(user))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already enabled");

        verify(userRepository, never()).save(any());
    }

    @Test
    void activateTwoFactor_quandoCodigoValido_deveAtivarTwoFactorEnabledTrue() {
        String secret = totpService.generateSecret();
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(false)
                .build();

        long window = FIXED_INSTANT.getEpochSecond() / 30L;
        String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

        twoFactorAuthService.activateTwoFactor(user, validCode);

        assertThat(user.getTwoFactorEnabled()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void activateTwoFactor_quandoCodigoInvalido_deveRejeitarEManterTwoFactorEnabledFalse() {
        String secret = totpService.generateSecret();
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(false)
                .build();

        assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(user, "000000"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getTwoFactorEnabled()).isFalse();
        verify(userRepository, never()).save(user);
    }

    @Test
    void activateTwoFactor_semSetupPrevio_deveLancarUnprocessableEntityException() {
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret(null)
                .twoFactorEnabled(false)
                .build();

        assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(user, "123456"))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void createLoginChallenge_paraUsuarioCom2faAtivo_naoDeveExporOtpauthUri() {
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                .twoFactorEnabled(true)
                .build();
        when(jwtTokenUtils.generateChallengeToken(user)).thenReturn("challenge-jwt");

        LoginResponseDTO response = twoFactorAuthService.createLoginChallenge(user);

        assertThat(response.getRequires2fa()).isTrue();
        assertThat(response.getChallengeToken()).isEqualTo("challenge-jwt");
        assertThat(response.getOtpauthUri()).isNull();
    }

    @Test
    void createLoginChallenge_paraAdminNoPrimeiroAcesso_deveGerarSegredoERetornarOtpauthUri() {
        AdministratorEntity admin = AdministratorEntity.builder()
                .email("admin@vstable.com")
                .require2fa(true)
                .twoFactorEnabled(false)
                .build();
        when(jwtTokenUtils.generateChallengeToken(admin)).thenReturn("admin-challenge-jwt");

        LoginResponseDTO response = twoFactorAuthService.createLoginChallenge(admin);

        assertThat(response.getRequires2fa()).isTrue();
        assertThat(response.getChallengeToken()).isEqualTo("admin-challenge-jwt");
        assertThat(response.getOtpauthUri()).startsWith("otpauth://totp/V-Stable:admin%40vstable.com?secret=");
        verify(administratorRepository).save(admin);
    }

    @Test
    void verifyLoginChallenge_quandoTokenECodigoValidos_deveRetornarPrincipalEBloquearReplay() {
        String secret = totpService.generateSecret();
        UserEntity user = UserEntity.builder()
                .email("user@vstable.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(true)
                .active(true)
                .build();

        long window = FIXED_INSTANT.getEpochSecond() / 30L;
        String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

        when(jwtTokenUtils.validateChallengeToken("valid-challenge")).thenReturn(true);
        when(jwtTokenUtils.getUsernameFromToken("valid-challenge")).thenReturn("user@vstable.com");
        when(jwtTokenUtils.getAccountTypeFromToken("valid-challenge")).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal("user@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);

        UserDetails result = twoFactorAuthService.verifyLoginChallenge("valid-challenge", validCode);
        assertThat(result).isSameAs(user);

        // Tentativa de Replay com o mesmo código na mesma janela deve falhar!
        assertThatThrownBy(() -> twoFactorAuthService.verifyLoginChallenge("valid-challenge", validCode))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void verifyLoginChallenge_apos5TentativasIncorretas_deveBloquearContaTemporariamente() {
        String secret = totpService.generateSecret();
        UserEntity user = UserEntity.builder()
                .email("brute@vstable.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(true)
                .active(true)
                .build();

        when(jwtTokenUtils.validateChallengeToken("challenge")).thenReturn(true);
        when(jwtTokenUtils.getUsernameFromToken("challenge")).thenReturn("brute@vstable.com");
        when(jwtTokenUtils.getAccountTypeFromToken("challenge")).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal("brute@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> twoFactorAuthService.verifyLoginChallenge("challenge", "000000"))
                    .isInstanceOf(BadCredentialsException.class);
        }

        // Na 6ª tentativa, mesmo antes de validar o TOTP, a conta está bloqueada com LockedException
        assertThatThrownBy(() -> twoFactorAuthService.verifyLoginChallenge("challenge", "000000"))
                .isInstanceOf(LockedException.class);
    }
}