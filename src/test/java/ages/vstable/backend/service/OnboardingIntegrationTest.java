package ages.vstable.backend.service;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OnboardingIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

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
    void performOnboarding_persistsCompanyUserAndKycWithRealMigration() {
        OnboardingResponseDTO response = onboardingService.performOnboarding(validRequest());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT legal_name FROM companies WHERE id = ?", String.class, response.getCompanyId()))
                .isEqualTo("Empresa Exemplo Ltda");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT city FROM companies WHERE id = ?", String.class, response.getCompanyId()))
                .isEqualTo("Porto Alegre");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_id FROM users WHERE id = ?", UUID.class, response.getUserId()))
                .isEqualTo(response.getCompanyId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_salt IS NOT NULL AND password_hash IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, response.getUserId()))
                .isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM avenia_kyc_verifications WHERE id = ?", UUID.class, response.getKycVerificationId()))
                .isEqualTo(response.getUserId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM avenia_kyc_verifications WHERE id = ?", String.class, response.getKycVerificationId()))
                .isEqualTo("PENDING");
    }

    @Test
    void performOnboarding_cnpjAlreadyRegistered_doesNotPersistUser() {
        onboardingService.performOnboarding(validRequest());

        OnboardingRequestDTO secondAttempt = validRequest();
        secondAttempt.setEmail("outro@example.com");

        assertThatThrownBy(() -> onboardingService.performOnboarding(secondAttempt))
                .isInstanceOf(ConflictException.class);

        Integer totalUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "outro@example.com");
        assertThat(totalUsers).isZero();
    }

    private OnboardingRequestDTO validRequest() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setFullName("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setPassword("Senha@123");
        request.setConfirmPassword("Senha@123");
        request.setLegalName("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }
}
