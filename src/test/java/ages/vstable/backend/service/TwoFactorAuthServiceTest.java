package ages.vstable.backend.service;

import ages.vstable.backend.dto.authentication.LoginResponseDTO;
import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                assertThat(user.getUpdatedAt()).isNotNull();
                verify(userRepository).save(user);
        }

        @Test
        void setupTwoFactor_paraAdmin_deveSalvarSegredoTemporarioNoRepositorioDeAdmins() {
                String oldSecret = totpService.generateSecret();
                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret(oldSecret)
                                .twoFactorEnabled(true)
                                .build();
                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCurrentCode = totpService.generateCodeForWindow(TotpService.decodeBase32(oldSecret), window);

                TwoFactorSetupResponseDTO response = twoFactorAuthService.setupTwoFactor(admin, validCurrentCode);

                assertThat(response.isTwoFactorEnabled()).isFalse();
                assertThat(response.getOtpauthUri()).startsWith("otpauth://totp/V-Stable:admin%40vstable.com?secret=");
                assertThat(admin.getTwoFactorSecret()).isNotBlank().isNotEqualTo(oldSecret);
                assertThat(admin.getTwoFactorEnabled()).isFalse();
                assertThat(admin.getUpdatedAt()).isNotNull();
                verify(administratorRepository).save(admin);
        }

        @Test
        void setupTwoFactor_quando2faJaAtivoESemCodigoAtualOuComCodigoInvalido_deveLancarBadCredentialsException() {
                UserEntity user = UserEntity.builder()
                                .email("user@vstable.com")
                                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                                .twoFactorEnabled(true)
                                .build();
                assertThatThrownBy(() -> twoFactorAuthService.setupTwoFactor(user))
                                .isInstanceOf(BadCredentialsException.class)
                                .hasMessageContaining("código TOTP atual é obrigatório");
                assertThatThrownBy(() -> twoFactorAuthService.setupTwoFactor(user, "000000"))
                                .isInstanceOf(BadCredentialsException.class);
                verify(userRepository, never()).save(any());
        }

        @Test
        void setupTwoFactor_paraPrincipalNaoSuportado_deveLancarIllegalArgumentException() {
                UserDetails unsupported = mock(UserDetails.class);

                assertThatThrownBy(() -> twoFactorAuthService.setupTwoFactor(unsupported))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Unsupported principal type");
        }

        @Test
        void setupTwoFactor_quando2faJaAtivoEComCodigoAtualValido_deveGerarNovoSegredoERevogarAntigo() {
                String oldSecret = totpService.generateSecret();
                UserEntity user = UserEntity.builder()
                                .email("user@vstable.com")
                                .twoFactorSecret(oldSecret)
                                .twoFactorEnabled(true)
                                .build();
                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCurrentCode = totpService.generateCodeForWindow(TotpService.decodeBase32(oldSecret),
                                window);
                TwoFactorSetupResponseDTO response = twoFactorAuthService.setupTwoFactor(user, validCurrentCode);
                assertThat(response.isTwoFactorEnabled()).isFalse();
                assertThat(response.getOtpauthUri()).isNotBlank();
                assertThat(user.getTwoFactorSecret()).isNotEqualTo(oldSecret);
                assertThat(user.getTwoFactorEnabled()).isFalse();
                verify(userRepository).save(user);
        }

        @Test
        void activateTwoFactor_quandoCodigoValidoParaUsuarioEAdmin_deveAtivar2fa() {
                String secret = totpService.generateSecret();
                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

                UserEntity user = UserEntity.builder()
                                .email("user@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(false)
                                .build();
                twoFactorAuthService.activateTwoFactor(user, validCode);
                assertThat(user.getTwoFactorEnabled()).isTrue();
                assertThat(user.getUpdatedAt()).isNotNull();
                verify(userRepository).save(user);

                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(false)
                                .build();
                twoFactorAuthService.activateTwoFactor(admin, validCode);
                assertThat(admin.getTwoFactorEnabled()).isTrue();
                assertThat(admin.getUpdatedAt()).isNotNull();
                verify(administratorRepository).save(admin);
        }

        @Test
        void activateTwoFactor_quandoCodigoInvalido_deveRejeitarEManterTwoFactorEnabledFalseParaUsuarioEAdmin() {
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

                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(false)
                                .build();

                assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(admin, "000000"))
                                .isInstanceOf(BadCredentialsException.class);

                assertThat(admin.getTwoFactorEnabled()).isFalse();
                verify(administratorRepository, never()).save(admin);
        }

        @Test
        void activateTwoFactor_quandoContaBloqueada_deveLancarLockedException() {
                String secret = totpService.generateSecret();
                UserEntity user = UserEntity.builder()
                                .email("locked-activate@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(false)
                                .build();
                for (int i = 0; i < 5; i++) {
                        assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(user, "000000"))
                                        .isInstanceOf(BadCredentialsException.class);
                }
                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

                assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(user, validCode))
                                .isInstanceOf(LockedException.class);
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

                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret("   ")
                                .twoFactorEnabled(false)
                                .build();

                assertThatThrownBy(() -> twoFactorAuthService.activateTwoFactor(admin, "123456"))
                                .isInstanceOf(UnprocessableEntityException.class);
        }

        @Test
        void requiresTwoFactorOnLogin_evaluatesUserAdminAndUnsupportedPrincipals() {
                UserEntity userEnabled = UserEntity.builder().twoFactorEnabled(true).build();
                UserEntity userDisabled = UserEntity.builder().twoFactorEnabled(false).build();
                AdministratorEntity adminEnabled = AdministratorEntity.builder()
                                .twoFactorEnabled(true).require2fa(false).build();
                AdministratorEntity adminRequired = AdministratorEntity.builder()
                                .twoFactorEnabled(false).require2fa(true).build();
                AdministratorEntity adminOptional = AdministratorEntity.builder()
                                .twoFactorEnabled(false).require2fa(false).build();
                UserDetails otherPrincipal = mock(UserDetails.class);

                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(userEnabled)).isTrue();
                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(userDisabled)).isFalse();
                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(adminEnabled)).isTrue();
                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(adminRequired)).isTrue();
                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(adminOptional)).isFalse();
                assertThat(twoFactorAuthService.requiresTwoFactorOnLogin(otherPrincipal)).isFalse();
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
                                .twoFactorEnabled(null)
                                .build();
                when(jwtTokenUtils.generateChallengeToken(admin)).thenReturn("admin-challenge-jwt");

                LoginResponseDTO response = twoFactorAuthService.createLoginChallenge(admin);

                assertThat(response.getRequires2fa()).isTrue();
                assertThat(response.getChallengeToken()).isEqualTo("admin-challenge-jwt");
                assertThat(response.getOtpauthUri()).startsWith("otpauth://totp/V-Stable:admin%40vstable.com?secret=");
                assertThat(admin.getTwoFactorSecret()).isNotBlank();
                assertThat(admin.getTwoFactorEnabled()).isFalse();
                assertThat(admin.getUpdatedAt()).isNotNull();
                verify(administratorRepository).save(admin);
        }

        @Test
        void createLoginChallenge_paraAdminComSegredoPendenteExistente_reutilizaSegredoSemSobrescrever() {
                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                                .require2fa(true)
                                .twoFactorEnabled(false)
                                .build();
                when(jwtTokenUtils.generateChallengeToken(admin)).thenReturn("admin-challenge-jwt");

                LoginResponseDTO response = twoFactorAuthService.createLoginChallenge(admin);

                assertThat(response.getRequires2fa()).isTrue();
                assertThat(response.getOtpauthUri()).contains("secret=JBSWY3DPEHPK3PXP");
                verify(administratorRepository, never()).save(any());
        }

        @Test
        void createLoginChallenge_paraUsuarioSemSegredoPendente_salvaSegredoPendenteNoUserRepository() {
                UserEntity user = UserEntity.builder()
                                .email("user@vstable.com")
                                .twoFactorSecret("   ")
                                .twoFactorEnabled(null)
                                .build();
                when(jwtTokenUtils.generateChallengeToken(user)).thenReturn("user-challenge-jwt");

                LoginResponseDTO response = twoFactorAuthService.createLoginChallenge(user);

                assertThat(response.getRequires2fa()).isTrue();
                assertThat(response.getChallengeToken()).isEqualTo("user-challenge-jwt");
                assertThat(response.getOtpauthUri()).startsWith("otpauth://totp/V-Stable:user%40vstable.com?secret=");
                assertThat(user.getTwoFactorSecret()).isNotBlank();
                assertThat(user.getTwoFactorEnabled()).isFalse();
                assertThat(user.getUpdatedAt()).isNotNull();
                verify(userRepository).save(user);
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
                when(jwtTokenUtils.getAccountTypeFromToken("valid-challenge"))
                                .thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
                when(userService.loadPrincipal("user@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);

                UserDetails result = twoFactorAuthService.verifyLoginChallenge("valid-challenge", validCode);
                assertThat(result).isSameAs(user);

                // Tentativa de Replay com o mesmo código na mesma janela deve falhar!
                assertThatThrownBy(() -> twoFactorAuthService.verifyLoginChallenge("valid-challenge", validCode))
                                .isInstanceOf(BadCredentialsException.class)
                                .hasMessageContaining("already been used");
        }

        @Test
        void verifyLoginChallenge_primeiroAcessoDeAdmin_deveMarcarTwoFactorEnabledComoTrue() {
                String secret = totpService.generateSecret();
                AdministratorEntity admin = AdministratorEntity.builder()
                                .email("admin@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(false)
                                .require2fa(true)
                                .active(true)
                                .build();

                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

                when(jwtTokenUtils.validateChallengeToken("admin-challenge")).thenReturn(true);
                when(jwtTokenUtils.getUsernameFromToken("admin-challenge")).thenReturn("admin@vstable.com");
                when(jwtTokenUtils.getAccountTypeFromToken("admin-challenge"))
                                .thenReturn(JwtTokenUtils.ACCOUNT_TYPE_ADMIN);
                when(userService.loadPrincipal("admin@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_ADMIN)).thenReturn(admin);

                UserDetails result = twoFactorAuthService.verifyLoginChallenge("admin-challenge", validCode);
                assertThat(result).isSameAs(admin);
                assertThat(admin.getTwoFactorEnabled()).isTrue();
                assertThat(admin.getUpdatedAt()).isNotNull();
                verify(administratorRepository).save(admin);
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

        @Test
        void validateEConsumeTransferTotp_naoConsomeNaValidacaoEBloqueiaReplayAposConsumo() {
                UUID userId = UUID.randomUUID();
                String secret = totpService.generateSecret();
                UserEntity user = UserEntity.builder()
                                .id(userId)
                                .email("transfer@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(true)
                                .build();
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

                // Validação não deve gastar o TOTP caso a transferência falhe antes do fim
                twoFactorAuthService.validateTransferTotp(userId, validCode);
                twoFactorAuthService.validateTransferTotp(userId, validCode);

                // Após consumir no final da operação, uma nova tentativa com o mesmo código é bloqueada
                twoFactorAuthService.consumeTransferTotp(userId, validCode);

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(userId, validCode))
                                .isInstanceOf(BadCredentialsException.class)
                                .hasMessageContaining("already been used");
        }

        @Test
        void validateTransferTotp_quandoUsuarioSem2faAtivo_lancaUnprocessableEntityException() {
                UUID userId = UUID.randomUUID();
                UserEntity user = UserEntity.builder()
                                .id(userId)
                                .email("no2fa@vstable.com")
                                .twoFactorSecret(totpService.generateSecret())
                                .twoFactorEnabled(false)
                                .build();
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(userId, "123456"))
                                .isInstanceOf(UnprocessableEntityException.class)
                                .hasMessageContaining("2FA");
        }

        @Test
        void validateTransferTotp_quandoCodigoAusente_lancaUnprocessableEntityException() {
                UUID userId = UUID.randomUUID();
                UserEntity user = UserEntity.builder()
                                .id(userId)
                                .email("transfer@vstable.com")
                                .twoFactorSecret(totpService.generateSecret())
                                .twoFactorEnabled(true)
                                .build();
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(userId, "   "))
                                .isInstanceOf(UnprocessableEntityException.class)
                                .hasMessageContaining("TOTP");
        }

        @Test
        void validateTransferTotp_quandoCodigoInvalido_lancaBadCredentialsException() {
                UUID userId = UUID.randomUUID();
                UserEntity user = UserEntity.builder()
                                .id(userId)
                                .email("transfer@vstable.com")
                                .twoFactorSecret(totpService.generateSecret())
                                .twoFactorEnabled(true)
                                .build();
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(userId, "000000"))
                                .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        void validateTransferTotp_quandoContaBloqueadaOuUsuarioNaoEncontrado_lancaExcecaoEsperada() {
                UUID missingUserId = UUID.randomUUID();
                when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(null, "123456"))
                                .isInstanceOf(BadCredentialsException.class)
                                .hasMessageContaining("User not authenticated");
                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(missingUserId, "123456"))
                                .isInstanceOf(BadCredentialsException.class)
                                .hasMessageContaining("Authenticated user not found");

                UUID lockedUserId = UUID.randomUUID();
                String secret = totpService.generateSecret();
                UserEntity lockedUser = UserEntity.builder()
                                .id(lockedUserId)
                                .email("locked-transfer@vstable.com")
                                .twoFactorSecret(secret)
                                .twoFactorEnabled(true)
                                .build();
                when(userRepository.findById(lockedUserId)).thenReturn(Optional.of(lockedUser));

                for (int i = 0; i < 5; i++) {
                        assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(lockedUserId, "000000"))
                                        .isInstanceOf(BadCredentialsException.class);
                }
                long window = FIXED_INSTANT.getEpochSecond() / 30L;
                String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), window);

                assertThatThrownBy(() -> twoFactorAuthService.validateTransferTotp(lockedUserId, validCode))
                                .isInstanceOf(LockedException.class);
        }
}
