package ages.vstable.backend.service;

import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

/**
 * Diagnóstico opt-in: asserções expressam o controle seguro esperado, portanto
 * falham enquanto o controle estiver vulnerável. Não chama AWS nem provedores.
 * Duas instâncias Java modelam memórias separadas, sem alegar reprodução na AWS.
 */
@Tag("security-assessment")
class TwoFactorStateAssessmentTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final AdministratorRepository administrators = mock(AdministratorRepository.class);
    private final UserService userService = mock(UserService.class);
    private final TotpService totp = new TotpService(Clock.fixed(NOW, ZoneOffset.UTC));
    private final JwtTokenUtils jwt = new JwtTokenUtils();
    private UserEntity user;
    private String challenge;
    private String code;

    @BeforeEach
    void arrangeSyntheticAccount() {
        byte[] signingMaterial = new byte[64];
        new SecureRandom().nextBytes(signingMaterial);
        ReflectionTestUtils.setField(jwt, "secret", Base64.getEncoder().encodeToString(signingMaterial));
        String secret = totp.generateSecret();
        user = UserEntity.builder().id(UUID.randomUUID()).email("totp-assessment@example.test")
                .active(true).twoFactorEnabled(true).twoFactorSecret(secret).build();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.loadPrincipal(user.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        challenge = jwt.generateChallengeToken(user);
        code = totp.generateCodeForWindow(TotpService.decodeBase32(secret), NOW.getEpochSecond() / 30);
    }

    @Test
    @DisplayName("TC-AUTH-01 | mesma instancia rejeita reutilizacao de TOTP")
    void sameInstanceRejectsReplay() {
        TwoFactorAuthService instance = instance();
        assertThat(instance.verifyLoginChallenge(challenge, code)).isSameAs(user);
        assertThatThrownBy(() -> instance.verifyLoginChallenge(challenge, code))
                .isInstanceOf(BadCredentialsException.class).hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("TC-AUTH-02 | segunda instancia deve rejeitar TOTP consumido pela primeira")
    void separateInstancesMustShareReplayProtection() {
        TwoFactorAuthService first = instance();
        TwoFactorAuthService second = instance();
        first.verifyLoginChallenge(challenge, code);
        assertThatThrownBy(() -> second.verifyLoginChallenge(challenge, code))
                .as("O consumo deve ser reconhecido em outra instancia do servico")
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("TC-AUTH-03 | reinicio deve preservar o controle contra reutilizacao")
    void newInstanceMustRetainConsumedCode() {
        instance().verifyLoginChallenge(challenge, code);
        TwoFactorAuthService restarted = instance();
        assertThatThrownBy(() -> restarted.verifyLoginChallenge(challenge, code))
                .as("Recriar o servico nao deve apagar o consumo do TOTP")
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("TC-AUTH-04 | bloqueio apos cinco erros deve valer em todas as instancias")
    void separateInstancesMustShareAccountLockout() {
        TwoFactorAuthService first = instance();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> first.verifyLoginChallenge(challenge, "invalid"))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> first.verifyLoginChallenge(challenge, code)).isInstanceOf(LockedException.class);
        assertThatThrownBy(() -> instance().verifyLoginChallenge(challenge, code))
                .as("A conta bloqueada nao deve autenticar em uma segunda instancia")
                .isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("TC-CONC-01 | validar e consumir TOTP deve autorizar no maximo uma operacao")
    void validationAndConsumptionMustBeAtomicAcrossFiveOperations() throws Exception {
        assertThat(acceptedTransferAttempts(instance()))
                .as("Operacoes autorizadas pelo mesmo TOTP").isEqualTo(1);
    }

    @ParameterizedTest(name = "LC-HARNESS-01 recusa atomica em {0}")
    @ValueSource(strings = {"validate", "consume"})
    void concurrentHarnessAcceptsCredentialRejectionsWithoutDeadlock(String rejectionPoint) throws Exception {
        TwoFactorAuthService secured = mock(TwoFactorAuthService.class);
        AtomicBoolean consumed = new AtomicBoolean();
        var claim = (org.mockito.stubbing.Answer<Void>) call -> {
            if (!consumed.compareAndSet(false, true)) {
                throw new BadCredentialsException("Synthetic replay rejection");
            }
            return null;
        };
        if (rejectionPoint.equals("validate")) {
            doAnswer(claim).when(secured).validateTransferTotp(user.getId(), code);
        } else {
            doAnswer(claim).when(secured).consumeTransferTotp(user.getId(), code);
        }
        // Contraprova da ferramenta: uma implementacao atomica simulada deve passar.
        assertThat(acceptedTransferAttempts(secured)).isEqualTo(1);
    }

    private int acceptedTransferAttempts(TwoFactorAuthService instance) throws Exception {
        CyclicBarrier start = new CyclicBarrier(5);
        CyclicBarrier validated = new CyclicBarrier(5);
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = IntStream.range(0, 5).mapToObj(index -> pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                boolean valid = false;
                try {
                    instance.validateTransferTotp(user.getId(), code);
                    valid = true;
                } catch (BadCredentialsException rejected) {
                    // Recusa esperada do controle; nao e erro de infraestrutura.
                } finally {
                    // Todos chegam aqui, inclusive quem foi recusado na validacao.
                    // Preserva a intercalacao deterministica do contrato dividido.
                    validated.await(10, TimeUnit.SECONDS);
                }
                if (!valid) return false;
                try {
                    instance.consumeTransferTotp(user.getId(), code);
                    return true;
                } catch (BadCredentialsException rejected) {
                    return false;
                }
            })).toList();
            int accepted = 0;
            for (var future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            return accepted;
        }
    }

    private TwoFactorAuthService instance() {
        return new TwoFactorAuthService(totp, users, administrators, jwt, userService);
    }
}
