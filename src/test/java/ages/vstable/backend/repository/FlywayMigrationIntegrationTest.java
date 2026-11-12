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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Valida as migrations num PostgreSQL real: a sequência V1..V13 sem versões duplicadas,
 * a V6 idempotente para bancos de desenvolvimento que já tinham os objetos de compliance
 * criados pela antiga V4 da branch BlindPay, na V11 o backfill dos vínculos e as
 * invariantes de vínculos e convites, na V12 o histórico de links substituídos e, na V13,
 * contas sem empresa preservando os dados existentes.
 * Ignorado quando não há Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    private static final String COMPANY_A = "00000000-0000-0000-0000-0000000000a1";
    private static final String COMPANY_B = "00000000-0000-0000-0000-0000000000b1";
    private static final String COMPANY_C = "00000000-0000-0000-0000-0000000000c1";
    private static final String USER_A1 = "00000000-0000-0000-0000-000000000a01";
    private static final String USER_A2 = "00000000-0000-0000-0000-000000000a02";
    private static final String USER_A3 = "00000000-0000-0000-0000-000000000a03";
    private static final String USER_B1 = "00000000-0000-0000-0000-000000000b01";
    private static final String USER_B2 = "00000000-0000-0000-0000-000000000b02";

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void resetSchema() throws SQLException {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
    }

    @Test
    void migrate_existingFiatDatabase_canPersistFourLetterTokenCurrencies() throws SQLException {
        flyway("9").migrate();
        execute("""
                INSERT INTO companies (id, legal_name, cnpj)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Sandbox Company', '1');
                INSERT INTO beneficiaries (id, company_id, nickname, currency)
                    VALUES ('00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001', 'Fiat beneficiary', 'BRL');
                """);

        flyway(null).migrate();

        execute("""
                UPDATE beneficiaries SET currency = 'USDC'
                    WHERE id = '00000000-0000-0000-0000-000000000002';
                INSERT INTO base_transactions (company_id, foreign_currency, foreign_amount)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'USDC', 10);
                """);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT b.currency, t.foreign_currency FROM beneficiaries b JOIN base_transactions t ON t.company_id = b.company_id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("USDC");
            assertThat(rows.getString(2)).isEqualTo("USDC");
        }
    }

    @Test
    void migrate_emptyDatabase_appliesAllVersionsInOrder() throws SQLException {
        MigrateResult result = flyway(null).migrate();

        assertThat(result.migrations).extracting(migration -> migration.version)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
        assertThat(columnExists("provider_accounts", "kyc_status")).isTrue();
        assertThat(columnExists("provider_wallets", "address")).isTrue();
        assertThat(columnExists("transfer_executions", "external_payout_id")).isTrue();
        assertThat(columnExists("administrators", "two_factor_secret")).isTrue();
        assertThat(columnExists("beneficiaries", "is_self_custody")).isTrue();
        assertThat(columnExists("beneficiaries", "counterparty_country")).isTrue();
        assertThat(tableExists("company_memberships")).isTrue();
        assertThat(tableExists("company_invitations")).isTrue();
        assertThat(tableExists("company_invitation_replaced_tokens")).isTrue();
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
        assertThat(result.migrations).extracting(migration -> migration.version)
                .containsExactly("6", "7", "8", "9", "10", "11", "12", "13");
        assertThat(columnExists("provider_accounts", "tos_id")).isTrue();
        assertThat(tableExists("blindpay_tos_sessions")).isTrue();
    }

    @Test
    void migrate_v13_keepsExistingUsersAndMembershipsAndAllowsAccountsWithoutCompany() throws SQLException {
        // Arrange: dados gravados antes da V13, quando a empresa era obrigatória na conta.
        flyway("12").migrate();
        insertCompany(COMPANY_A, "1");
        insertUser(USER_A1, COMPANY_A, "a1@example.com", "'2026-01-01T00:00:00Z'");
        insertUser(USER_A2, COMPANY_A, "a2@example.com", "'2026-02-01T00:00:00Z'");
        insertMembership(USER_A1, COMPANY_A, "OWNER", "ACTIVE");
        insertMembership(USER_A2, COMPANY_A, "OPERATOR", "REMOVED");

        // Act
        MigrateResult result = flyway(null).migrate();
        execute("INSERT INTO users (id, company_id, full_name, email, password_hash, password_salt) "
                + "VALUES ('" + USER_A3 + "', NULL, 'Sem empresa', 'nova@example.com', 'h', 's')");

        // Assert
        assertThat(result.migrations).extracting(migration -> migration.version).containsExactly("13");
        assertThat(rows("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'users' AND column_name = 'company_id'")).containsExactly("YES");
        assertThat(rows("SELECT id || ':' || company_id FROM users WHERE company_id IS NOT NULL"))
                .containsExactlyInAnyOrder(USER_A1 + ":" + COMPANY_A, USER_A2 + ":" + COMPANY_A);
        assertThat(rows("SELECT user_id || ':' || role || ':' || status FROM company_memberships"))
                .containsExactlyInAnyOrder(USER_A1 + ":OWNER:ACTIVE", USER_A2 + ":OPERATOR:REMOVED");
        assertThat(rows("SELECT id FROM users WHERE company_id IS NULL")).containsExactly(USER_A3);
    }

    @Test
    void users_companyThatDoesNotExist_isStillRejectedAfterV13() throws SQLException {
        flyway(null).migrate();

        assertThatThrownBy(() -> insertUser(USER_A1, COMPANY_B, "a1@example.com", "NULL"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("users_company_id_fkey");
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

    @Test
    void membershipBackfill_companyWithSingleUser_makesThatUserTheActiveOwner() throws SQLException {
        // Arrange
        flyway("10").migrate();
        insertCompany(COMPANY_A, "1");
        insertUser(USER_A1, COMPANY_A, "a1@example.com", "'2026-01-01T00:00:00Z'");

        // Act
        flyway(null).migrate();

        // Assert
        assertThat(rows("SELECT user_id || ':' || role || ':' || status || ':' || version FROM company_memberships"))
                .containsExactly(USER_A1 + ":OWNER:ACTIVE:1");
    }

    @Test
    void membershipBackfill_companyWithSeveralUsers_promotesNobodyAndKeepsEveryoneAsOperator() throws SQLException {
        // Arrange: um dos usuários vem de uma linha antiga sem created_at.
        flyway("10").migrate();
        insertCompany(COMPANY_A, "1");
        insertUser(USER_A1, COMPANY_A, "a1@example.com", "'2026-01-01T00:00:00Z'");
        insertUser(USER_A2, COMPANY_A, "a2@example.com", "'2026-02-01T00:00:00Z'");
        insertUser(USER_A3, COMPANY_A, "a3@example.com", "NULL");

        // Act
        flyway(null).migrate();

        // Assert
        assertThat(rows("SELECT role FROM company_memberships")).containsExactly("OPERATOR", "OPERATOR", "OPERATOR");
        assertThat(rows("SELECT status FROM company_memberships WHERE joined_at IS NULL OR status <> 'ACTIVE'"))
                .isEmpty();
    }

    @Test
    void membershipBackfill_severalCompanies_decidesTheOwnerOfEachOneIndependently() throws SQLException {
        // Arrange: A tem um usuário, B tem dois e C não tem nenhum.
        flyway("10").migrate();
        insertCompany(COMPANY_A, "1");
        insertCompany(COMPANY_B, "2");
        insertCompany(COMPANY_C, "3");
        insertUser(USER_A1, COMPANY_A, "a1@example.com", "'2026-01-01T00:00:00Z'");
        insertUser(USER_B1, COMPANY_B, "b1@example.com", "'2026-01-01T00:00:00Z'");
        insertUser(USER_B2, COMPANY_B, "b2@example.com", "'2026-02-01T00:00:00Z'");

        // Act
        flyway(null).migrate();

        // Assert
        assertThat(rows("SELECT company_id || ':' || role FROM company_memberships WHERE role = 'OWNER'"))
                .containsExactly(COMPANY_A + ":OWNER");
        assertThat(rows("SELECT company_id FROM company_memberships WHERE company_id = '" + COMPANY_C + "'"))
                .isEmpty();
        assertThat(rows("SELECT user_id FROM company_memberships WHERE company_id = '" + COMPANY_B + "'"))
                .containsExactlyInAnyOrder(USER_B1, USER_B2);
    }

    @Test
    void membershipBackfill_leavesCredentialsTwoFactorAndCompanyOfTheUsersUntouched() throws SQLException {
        // Arrange
        flyway("10").migrate();
        insertCompany(COMPANY_A, "1");
        execute("INSERT INTO users (id, company_id, full_name, email, password_hash, password_salt, "
                + "two_factor_secret, two_factor_enabled) VALUES ('" + USER_A1 + "', '" + COMPANY_A
                + "', 'User', 'a1@example.com', 'hash-intacto', 'salt-intacto', 'segredo-2fa-intacto', true)");

        // Act
        flyway(null).migrate();

        // Assert
        assertThat(rows("SELECT company_id || ':' || password_hash || ':' || password_salt || ':' "
                + "|| two_factor_secret || ':' || two_factor_enabled FROM users"))
                .containsExactly(COMPANY_A + ":hash-intacto:salt-intacto:segredo-2fa-intacto:true");
    }

    @Test
    void memberships_secondActiveOwnerInTheSameCompany_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertMembership(USER_A1, COMPANY_A, "OWNER", "ACTIVE");

        // Act / Assert
        assertThatThrownBy(() -> insertMembership(USER_A2, COMPANY_A, "OWNER", "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_memberships_active_owner_key");
    }

    @Test
    void memberships_secondActiveMembershipForTheSameUser_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertCompany(COMPANY_B, "2");
        insertMembership(USER_A1, COMPANY_A, "OPERATOR", "ACTIVE");

        // Act / Assert
        assertThatThrownBy(() -> insertMembership(USER_A1, COMPANY_B, "OPERATOR", "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_memberships_active_user_key");
    }

    @Test
    void memberships_removedMembershipDoesNotBlockANewActiveOneForTheSameUser() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertMembership(USER_A1, COMPANY_A, "OWNER", "REMOVED");

        // Act
        insertMembership(USER_A1, COMPANY_A, "OPERATOR", "ACTIVE");

        // Assert
        assertThat(rows("SELECT role || ':' || status FROM company_memberships ORDER BY role"))
                .containsExactly("OPERATOR:ACTIVE", "OWNER:REMOVED");
    }

    @Test
    void memberships_removedWithoutRemovalDate_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();

        // Act / Assert
        assertThatThrownBy(() -> execute("INSERT INTO company_memberships (company_id, user_id, role, status) "
                + "VALUES ('" + COMPANY_A + "', '" + USER_A1 + "', 'OPERATOR', 'REMOVED')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_memberships_removed_at_consistent");
    }

    @Test
    void invitations_pendingWithoutRecoverableToken_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();

        // Act / Assert
        assertThatThrownBy(() -> insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-1", false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitations_recoverable_token_only_pending");
    }

    @Test
    void invitations_acceptedKeepingRecoverableToken_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();

        // Act / Assert
        assertThatThrownBy(() -> insertInvitation("ana@example.com", "OPERATOR", "ACCEPTED", "hash-1", true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitations_recoverable_token_only_pending");
    }

    @Test
    void invitations_ownerRole_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();

        // Act / Assert
        assertThatThrownBy(() -> insertInvitation("ana@example.com", "OWNER", "PENDING", "hash-1", true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitations_role_not_owner");
    }

    @Test
    void invitations_secondPendingForTheSameEmailIgnoringCase_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertInvitation("Ana@Example.com", "OPERATOR", "PENDING", "hash-1", true);

        // Act / Assert
        assertThatThrownBy(() -> insertInvitation("ana@example.com", "VIEWER", "PENDING", "hash-2", true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitations_pending_email_key");
    }

    @Test
    void invitations_newPendingAfterRevocationForTheSameEmail_isAllowed() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertInvitation("ana@example.com", "OPERATOR", "REVOKED", "hash-1", false);

        // Act
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-2", true);

        // Assert
        assertThat(rows("SELECT status FROM company_invitations ORDER BY status"))
                .containsExactly("PENDING", "REVOKED");
    }

    @Test
    void invitations_repeatedTokenHash_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-1", true);

        // Act / Assert
        assertThatThrownBy(() -> insertInvitation("beto@example.com", "VIEWER", "PENDING", "hash-1", true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitations_token_hash_key");
    }

    @Test
    void replacedTokens_sameHashTwice_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-current", true);
        insertReplacedToken("hash-old");

        // Act / Assert
        assertThatThrownBy(() -> insertReplacedToken("hash-old"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitation_replaced_tokens_token_hash_key");
    }

    @Test
    void replacedTokens_unknownInvitation_isRejected() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();

        // Act / Assert
        assertThatThrownBy(() -> execute("INSERT INTO company_invitation_replaced_tokens (invitation_id, token_hash) "
                + "VALUES ('00000000-0000-0000-0000-0000000000ff', 'hash-old')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("company_invitation_replaced_tokens_invitation_fkey");
    }

    @Test
    void deletingInvitation_removesItsReplacedTokens() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-current", true);
        insertReplacedToken("hash-old");

        // Act
        execute("DELETE FROM company_invitations");

        // Assert
        assertThat(rows("SELECT id FROM company_invitation_replaced_tokens")).isEmpty();
    }

    @Test
    void deletingCompany_removesItsMembershipsAndInvitations() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertMembership(USER_A1, COMPANY_A, "OWNER", "ACTIVE");
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-1", true);

        // Act
        execute("DELETE FROM companies WHERE id = '" + COMPANY_A + "'");

        // Assert
        assertThat(rows("SELECT id FROM company_memberships")).isEmpty();
        assertThat(rows("SELECT id FROM company_invitations")).isEmpty();
    }

    @Test
    void deletingUser_removesItsMembershipsAndTheInvitationsItCreated() throws SQLException {
        // Arrange
        migrateWithCompanyAndTwoUsers();
        insertMembership(USER_A1, COMPANY_A, "OWNER", "ACTIVE");
        insertMembership(USER_A2, COMPANY_A, "OPERATOR", "ACTIVE");
        insertInvitation("ana@example.com", "OPERATOR", "PENDING", "hash-1", true);

        // Act
        execute("DELETE FROM users WHERE id = '" + USER_A1 + "'");

        // Assert
        assertThat(rows("SELECT user_id FROM company_memberships")).containsExactly(USER_A2);
        assertThat(rows("SELECT id FROM company_invitations")).isEmpty();
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

    private void migrateWithCompanyAndTwoUsers() throws SQLException {
        flyway(null).migrate();
        insertCompany(COMPANY_A, "1");
        insertUser(USER_A1, COMPANY_A, "a1@example.com", "'2026-01-01T00:00:00Z'");
        insertUser(USER_A2, COMPANY_A, "a2@example.com", "'2026-02-01T00:00:00Z'");
    }

    private void insertCompany(String id, String cnpj) throws SQLException {
        execute("INSERT INTO companies (id, legal_name, cnpj) VALUES ('" + id + "', 'Acme', '" + cnpj + "')");
    }

    /** {@code createdAt} é um literal SQL: uma data entre aspas ou NULL. */
    private void insertUser(String id, String companyId, String email, String createdAt) throws SQLException {
        execute("INSERT INTO users (id, company_id, full_name, email, password_hash, password_salt, created_at) "
                + "VALUES ('" + id + "', '" + companyId + "', 'User', '" + email + "', 'h', 's', " + createdAt + ")");
    }

    private void insertMembership(String userId, String companyId, String role, String status) throws SQLException {
        String removedAt = "REMOVED".equals(status) ? "now()" : "NULL";
        execute("INSERT INTO company_memberships (company_id, user_id, role, status, removed_at) VALUES ('"
                + companyId + "', '" + userId + "', '" + role + "', '" + status + "', " + removedAt + ")");
    }

    private void insertInvitation(String email, String role, String status, String tokenHash,
                                  boolean recoverableToken) throws SQLException {
        String ciphertext = recoverableToken ? "'ciphertext'" : "NULL";
        String keyVersion = recoverableToken ? "'v1'" : "NULL";
        execute("INSERT INTO company_invitations (company_id, name, email, role, status, token_hash, "
                + "token_ciphertext, token_key_version, created_by) VALUES ('" + COMPANY_A + "', 'Convidado', '"
                + email + "', '" + role + "', '" + status + "', '" + tokenHash + "', " + ciphertext + ", "
                + keyVersion + ", '" + USER_A1 + "')");
    }

    /** Registra {@code tokenHash} como link substituído do único convite criado pelo teste. */
    private void insertReplacedToken(String tokenHash) throws SQLException {
        execute("INSERT INTO company_invitation_replaced_tokens (invitation_id, token_hash) "
                + "SELECT id, '" + tokenHash + "' FROM company_invitations");
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
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
