package ages.vstable.backend.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Duas instâncias independentes do store, compartilhando somente o PostgreSQL.
 * Testa a garantia exigida pelo deploy em múltiplas AWS Lambdas sem simular banco em memória.
 */
@Testcontainers(disabledWithoutDocker = true)
class TotpReplayStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    private static DriverManagerDataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TotpReplayStore firstInstance;
    private TotpReplayStore secondInstance;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM public.totp_used_steps");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        firstInstance = new TotpReplayStore(new JdbcTemplate(dataSource));
        secondInstance = new TotpReplayStore(new JdbcTemplate(dataSource));
    }

    @Test
    void consumedCode_isRejectedByAnotherInstanceAndNoEmailIsStored() {
        String email = "Owner@Example.COM";
        firstInstance.consume(email, 100L);

        assertThat(secondInstance.wasConsumed("owner@example.com", 100L)).isTrue();
        assertThatThrownBy(() -> secondInstance.consume(email, 100L))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.totp_used_steps", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT principal_key FROM public.totp_used_steps", String.class))
                .doesNotContain("example.com");
    }

    @Test
    void olderStepCannotReplaceNewerStep_andOtherAccountsAreIndependent() {
        String email = UUID.randomUUID() + "@example.test";
        firstInstance.consume(email, 102L);

        assertThatThrownBy(() -> secondInstance.consume(email, 101L))
                .isInstanceOf(BadCredentialsException.class);
        secondInstance.consume("other@example.test", 101L);
        firstInstance.consume(email, 103L);

        assertThat(secondInstance.wasConsumed(email, 103L)).isTrue();
        assertThat(secondInstance.wasConsumed(email, 104L)).isFalse();
    }

    @Test
    void rolledBackConfirmation_doesNotConsumeCodeInEitherInstance() {
        String email = "rollback@example.test";

        transactions.executeWithoutResult(status -> {
            firstInstance.consume(email, 200L);
            assertThat(secondInstance.wasConsumed(email, 200L)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(secondInstance.wasConsumed(email, 200L)).isFalse();
        secondInstance.consume(email, 200L);
        assertThat(firstInstance.wasConsumed(email, 200L)).isTrue();
    }

    @Test
    void simultaneousConfirmationAcrossInstances_onlyOneConsumesTheSameStep() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(requests)) {
            List<java.util.concurrent.Future<Object>> futures = java.util.stream.IntStream.range(0, requests)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        try {
                            transactions.executeWithoutResult(status ->
                                    (i % 2 == 0 ? firstInstance : secondInstance).consume(email, 500L));
                            accepted.incrementAndGet();
                        } catch (BadCredentialsException expected) {
                            blocked.incrementAndGet();
                        }
                        return null;
                    })).toList();

            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(blocked.get()).isEqualTo(requests - 1);
    }
}
