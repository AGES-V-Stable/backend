package ages.vstable.backend.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Valida as migrations num PostgreSQL real: a sequência V1..V7 sem versões duplicadas e
 * a V6 idempotente para bancos de desenvolvimento que já tinham os objetos de compliance
 * criados pela antiga V4 da branch BlindPay. Ignorado quando não há Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void resetSchema() throws SQLException {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
    }

    @Test
    void migrate_emptyDatabase_appliesAllVersionsInOrder() throws SQLException {
        MigrateResult result = flyway(null).migrate();

        assertThat(result.migrations).extracting(migration -> migration.version)
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(columnExists("provider_accounts", "kyc_status")).isTrue();
        assertThat(columnExists("provider_wallets", "address")).isTrue();
        assertThat(columnExists("transfer_executions", "external_payout_id")).isTrue();
    }

    @Test
    void migrate_databaseWithComplianceObjectsFromOldBranchV4_appliesV6WithoutFailing() throws SQLException {
        // Arrange: banco já na V5 da main que recebeu manualmente os objetos da antiga V4.
        flyway("5").migrate();
        execute("""
                ALTER TABLE provider_accounts ADD COLUMN kyc_status varchar(50);
                CREATE TABLE blindpay_webhook_events (message_id varchar(255) PRIMARY KEY,
                    event_type varchar(50), received_at timestamptz NOT NULL DEFAULT now());
                """);

        // Act
        MigrateResult result = flyway(null).migrate();

        // Assert
        assertThat(result.migrations).extracting(migration -> migration.version).containsExactly("6", "7");
        assertThat(columnExists("provider_accounts", "tos_id")).isTrue();
        assertThat(tableExists("blindpay_tos_sessions")).isTrue();
    }

    @Test
    void transferExecutions_sameOfferCannotBeExecutedTwice() throws SQLException {
        flyway(null).migrate();
        execute("""
                INSERT INTO companies (id, legal_name, cnpj) VALUES ('00000000-0000-0000-0000-000000000001', 'Acme', '1');
                INSERT INTO users (id, company_id, full_name, email, password_hash, password_salt)
                    VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
                            'User', 'user@example.com', 'h', 's');
                INSERT INTO beneficiaries (id, company_id, nickname)
                    VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'Vendor');
                INSERT INTO quote_requests (id, company_id, requested_by, beneficiary_id, direction, source_currency,
                    target_currency, source_payment_method, target_payment_method, requested_amount, amount_side, status)
                    VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                            'PAYOUT', 'BRL', 'USD', 'pix', 'wire', 100, 'SOURCE', 'COMPLETED');
                INSERT INTO provider_quotes (id, quote_request_id, provider, idempotency_key, status)
                    VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000004',
                            'BLINDPAY', 'quote:1:blindpay', 'CONFIRMED');
                """);
        String insertExecution = """
                INSERT INTO transfer_executions (provider_quote_id, company_id, requested_by, beneficiary_id,
                    provider, status, source_currency, target_currency, source_amount, target_amount)
                VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                        'BLINDPAY', 'PENDING_SUBMISSION', 'BRL', 'USD', 100, 99)
                """;
        execute(insertExecution);

        assertThatThrownBy(() -> execute(insertExecution))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("transfer_executions_provider_quote_key");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        return exists("SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '"
                + table + "' AND column_name = '" + column + "'");
    }

    private boolean tableExists(String table) throws SQLException {
        return exists("SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '"
                + table + "'");
    }

    private boolean exists(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next();
        }
    }
}
