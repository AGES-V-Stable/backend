package ages.vstable.backend.service;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static ages.vstable.backend.support.OnboardingRequestFixtures.requestValido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM avenia_kyc_verifications");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM companies");
    }

    @Test
    void realizarOnboarding_persisteEmpresaUsuarioEKycComMigrationReal() {
        OnboardingResponseDTO response = onboardingService.realizarOnboarding(requestValido());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal_name FROM companies WHERE id = ?", String.class, response.getEmpresaId()))
                .isEqualTo("Empresa Exemplo Ltda");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT city FROM companies WHERE id = ?", String.class, response.getEmpresaId()))
                .isEqualTo("Porto Alegre");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_id FROM users WHERE id = ?", UUID.class, response.getUsuarioId()))
                .isEqualTo(response.getEmpresaId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_salt IS NOT NULL AND password_hash IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, response.getUsuarioId()))
                .isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM avenia_kyc_verifications WHERE id = ?", UUID.class, response.getVerificacaoKycId()))
                .isEqualTo(response.getUsuarioId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM avenia_kyc_verifications WHERE id = ?", String.class, response.getVerificacaoKycId()))
                .isEqualTo("PENDENTE");
    }

    @Test
    void realizarOnboarding_cnpjJaCadastrado_naoPersisteUsuario() {
        onboardingService.realizarOnboarding(requestValido());

        OnboardingRequestDTO segundaTentativa = requestValido();
        segundaTentativa.setEmail("outro@example.com");

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(segundaTentativa))
                .isInstanceOf(ConflictException.class);

        Integer totalUsuarios = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "outro@example.com");
        assertThat(totalUsuarios).isZero();
    }
}
