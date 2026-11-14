package ages.vstable.backend.service;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Verifica o crédito simulado da backend#38 contra o PostgreSQL e a migration reais.
 * O cadastro/KYC é isolado por mock; toda leitura, escrita, transação e row lock do saldo
 * usam componentes Spring e PostgreSQL reais. Ignorado se Docker não estiver disponível.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BalancePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("balance.simulated-deposits.enabled", () -> "true");
    }

    @Autowired
    private BalanceService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AveniaKycVerificationRepository kycRepository;

    @BeforeEach
    void cleanCompanies() {
        jdbcTemplate.update("DELETE FROM companies");
    }

    @Test
    void repeatedCreditsPersistAcrossReadsAndOtherMembersOfTheSameCompany() {
        UUID company = company("1001", "0.00");
        UserEntity owner = approvedOwner(company);
        UserEntity colleague = approvedOwner(company);

        assertThat(service.getBalance(company).availableBalanceBrl()).isEqualTo("0.00");
        assertThat(service.addBalance(owner, new BigDecimal("10.25")).availableBalanceBrl())
                .isEqualTo("10.25");
        assertThat(service.addBalance(colleague, new BigDecimal("2.50")).availableBalanceBrl())
                .isEqualTo("12.75");

        assertThat(balanceInDatabase(company)).isEqualByComparingTo("12.75");
        assertThat(service.getBalance(company).availableBalanceBrl()).isEqualTo("12.75");
    }

    @Test
    void creditsCannotAffectAnotherCompanyBalance() {
        UUID first = company("1002", "40.00");
        UUID second = company("1003", "50.00");

        assertThat(service.addBalance(approvedOwner(first), new BigDecimal("2.25")).availableBalanceBrl())
                .isEqualTo("42.25");
        assertThat(balanceInDatabase(first)).isEqualByComparingTo("42.25");
        assertThat(balanceInDatabase(second)).isEqualByComparingTo("50.00");
    }

    @Test
    void concurrentCreditsOnTheSameCompanyDoNotLoseUpdates() throws Exception {
        UUID company = company("1004", "0.00");
        UserEntity owner = approvedOwner(company);
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                Callable<String> credit = () -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Credit test did not start");
                    }
                    return service.addBalance(owner, new BigDecimal("0.25")).availableBalanceBrl();
                };
                futures.add(pool.submit(credit));
            }

            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isNotBlank();
            }
            assertThat(balanceInDatabase(company)).isEqualByComparingTo("2.00");
            assertThat(service.getBalance(company).availableBalanceBrl()).isEqualTo("2.00");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void overflowRejectsCreditWithoutChangingPersistedBalance() {
        UUID company = company("1005", "9999999999999.99");
        UserEntity owner = approvedOwner(company);

        assertThatThrownBy(() -> service.addBalance(owner, new BigDecimal("0.01")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "BALANCE_LIMIT_EXCEEDED");
        assertThat(balanceInDatabase(company)).isEqualByComparingTo("9999999999999.99");
    }

    private UUID company(String suffix, String startingBalance) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO companies (id, legal_name, cnpj, country, state, available_balance_brl)
                VALUES (?, ?, ?, 'Brasil', 'RS', ?)
                """, id, "Saldo Teste " + suffix, "0000000000" + suffix, new BigDecimal(startingBalance));
        return id;
    }

    private UserEntity approvedOwner(UUID companyId) {
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(java.util.Optional.of(AveniaKycVerificationEntity.builder()
                        .status(ComplianceStatus.APPROVED).build()));
        return user;
    }

    private BigDecimal balanceInDatabase(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT available_balance_brl FROM companies WHERE id = ?",
                BigDecimal.class, companyId);
    }
}
