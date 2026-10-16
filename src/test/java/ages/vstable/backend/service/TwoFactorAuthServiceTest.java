package ages.vstable.backend.service;

import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TwoFactorAuthServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-16T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdministratorRepository administratorRepository;

    private TotpService totpService;
    private TwoFactorAuthService twoFactorAuthService;

    @BeforeEach
    void setUp() {
        totpService = new TotpService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        twoFactorAuthService = new TwoFactorAuthService(totpService, userRepository, administratorRepository);
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
}