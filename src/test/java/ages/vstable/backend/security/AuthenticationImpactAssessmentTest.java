package ages.vstable.backend.security;

import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TotpService;
import ages.vstable.backend.service.TwoFactorAuthService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Duas instâncias locais e um relógio fixo; não é uma execução de AWS Lambda. */
@Tag("pentest-impact")
class AuthenticationImpactAssessmentTest extends ImpactEvidence {
    private final Instant now = Instant.parse("2026-11-13T12:00:00Z");
    private final TotpService totp = new TotpService(Clock.fixed(now, ZoneOffset.UTC));
    private final UserRepository users = mock(UserRepository.class);
    private final AdministratorRepository admins = mock(AdministratorRepository.class);
    private final UserService userService = mock(UserService.class);
    private final JwtTokenUtils jwt = new JwtTokenUtils();
    private UserEntity user;
    private String challenge;
    private String code;

    @BeforeEach
    void arrange() {
        ReflectionTestUtils.setField(jwt, "secret", "local-only-pentest-signing-material-" + UUID.randomUUID() + UUID.randomUUID());
        String secret = totp.generateSecret();
        user = UserEntity.builder().id(UUID.randomUUID()).email("totp@example.test").active(true)
                .twoFactorEnabled(true).twoFactorSecret(secret).build();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.loadPrincipal(user.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        challenge = jwt.generateChallengeToken(user);
        byte[] key = ReflectionTestUtils.invokeMethod(totp, "decodeBase32", secret);
        code = ReflectionTestUtils.invokeMethod(totp, "generateCodeForWindow", key, now.getEpochSecond() / 30);
        assertThat(totp.verifyCode(secret, code)).isTrue();
    }

    @Test
    @DisplayName("P86-16 CHAIN-01 replay local bloqueado mas aceito em segunda instancia e reinicio")
    void sameCodeAcceptedByIndependentServiceInstances() {
        TwoFactorAuthService first = instance();
        assertThat(first.verifyLoginChallenge(challenge, code)).isSameAs(user);
        assertThatThrownBy(() -> first.verifyLoginChallenge(challenge, code)).isInstanceOf(BadCredentialsException.class);
        assertThat(instance().verifyLoginChallenge(challenge, code)).isSameAs(user);
        assertThat(instance().verifyLoginChallenge(challenge, code)).isSameAs(user);
        observe("resultado", java.util.Map.of("primeiraInstancia", "aceitou e bloqueou replay", "segundaInstancia", "aceitou mesmo codigo", "servicoRecriado", "aceitou mesmo codigo"));
        observe("limite", "repositories de identidade simulados; TOTP e JWT reais; nenhuma Lambda iniciada");
    }

    @Test
    @DisplayName("P86-17 CHAIN-01 lockout de cinco erros nao acompanha segunda instancia")
    void lockoutDoesNotPropagateToSecondInstance() {
        TwoFactorAuthService first = instance();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> first.verifyLoginChallenge(challenge, "invalid")).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> first.verifyLoginChallenge(challenge, code)).isInstanceOf(LockedException.class);
        assertThat(instance().verifyLoginChallenge(challenge, code)).isSameAs(user);
        observe("resultado", java.util.Map.of("tentativasInvalidas", 5, "instanciaA", "LockedException", "instanciaB", "autenticou"));
    }

    @Test
    @DisplayName("P86-18 CHAIN-01 cinco validacoes intercaladas consomem o mesmo fator")
    void controlledInterleavingAuthorizesFiveServiceOperations() throws Exception {
        var service = instance();
        CyclicBarrier checked = new CyclicBarrier(5);
        try (var pool = Executors.newFixedThreadPool(5)) {
            var work = IntStream.range(0, 5).mapToObj(i -> pool.submit(() -> {
                service.validateTransferTotp(user.getId(), code);
                // Escalonamento de teste deliberado: todas validam antes de qualquer consumo.
                checked.await(10, TimeUnit.SECONDS);
                service.consumeTransferTotp(user.getId(), code);
                return true;
            })).toList();
            int accepted = 0;
            for (var future : work) if (future.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
            observe("validacoesEConsumosConcluidos", accepted);
        }
        observe("limite", "Intercalamento controlado do servico; nao demonstra cinco transferencias, nem incidencia probabilistica em producao");
    }

    private TwoFactorAuthService instance() { return new TwoFactorAuthService(totp, users, admins, jwt, userService); }
}
