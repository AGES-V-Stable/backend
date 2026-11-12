package ages.vstable.backend.controller;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conta e empresa em etapas separadas (backend#64) pela cadeia HTTP real: segurança, JWT real com o vínculo
 * recarregado a cada requisição, migration V13 (users.company_id opcional) e restrições únicas do PostgreSQL,
 * inclusive em requisições simultâneas. Nenhum caminho chama provedores externos. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountAndCompanyRegistrationIntegrationTest {

    private static final String CNPJ = "11.222.333/0001-81";
    private static final String NORMALIZED_CNPJ = "11222333000181";
    private static final String OTHER_CNPJ = "45.997.418/0001-53";
    private static final String PASSWORD = "Senha@123";
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 30;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM company_memberships");
        jdbcTemplate.update("DELETE FROM avenia_kyc_verifications");
        // Usuários sem empresa não são removidos em cascata pela exclusão das empresas.
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM administrators");
    }

    @Test
    void accountWithoutCompany_authenticatesButIsDeniedCompanyData() throws Exception {
        // Act
        String token = createAccount("joao@example.com");

        // Assert
        mockMvc.perform(authorized(get("/v1/users/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("joao@example.com"))
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.companyAccess").value(nullValue()));
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(get("/v1/onboarding/me"), token))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_id FROM users WHERE email = ?", UUID.class, "joao@example.com")).isNull();
        assertThat(count("company_memberships")).isZero();
        assertThat(count("avenia_kyc_verifications")).isZero();
    }

    @Test
    void accountWithoutCompany_canLogInWithItsPassword() throws Exception {
        // Arrange
        createAccount("joao@example.com");

        // Act
        String authorization = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"joao@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.AUTHORIZATION))
                .andReturn().getResponse().getHeader(HttpHeaders.AUTHORIZATION);

        // Assert
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(nullValue()));
    }

    @Test
    void accountWithTwoFactor_receivesTheSetupUriAndStaysPendingActivation() throws Exception {
        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("joao@example.com").replace("{", "{\"enableTwoFactor\":true,")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.twoFactorSetupRequired").value(true))
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT two_factor_enabled FROM users WHERE email = ?", Boolean.class, "joao@example.com")).isFalse();
    }

    @Test
    void registeringTheCompany_makesTheUserItsOwnerWithTheSameToken() throws Exception {
        // Arrange
        String token = createAccount("joao@example.com");

        // Act
        String body = registerCompany(token, CNPJ)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        // Assert
        String companyId = JsonPath.read(body, "$.companyId");
        String kycId = JsonPath.read(body, "$.kycVerificationId");
        mockMvc.perform(authorized(get("/v1/users/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"))
                .andExpect(jsonPath("$.companyAccess.status").value("ACTIVE"));
        mockMvc.perform(authorized(get("/v1/balance"), token)).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/v1/onboarding/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycVerificationId").value(kycId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT kyb_status || ':' || aml_status FROM companies WHERE id = ?::uuid", String.class, companyId))
                .isEqualTo("PENDING:PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_id::text FROM users WHERE email = ?", String.class, "joao@example.com"))
                .isEqualTo(companyId);
    }

    @Test
    void aUserCannotRegisterASecondCompany() throws Exception {
        // Arrange
        String token = createAccount("joao@example.com");
        registerCompany(token, CNPJ).andExpect(status().isCreated());

        // Act / Assert
        registerCompany(token, OTHER_CNPJ)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_IN_COMPANY"));
        assertThat(count("companies")).isEqualTo(1);
        assertThat(count("company_memberships")).isEqualTo(1);
    }

    @Test
    void knowingTheCnpjOfAnExistingCompany_neverGrantsAccessToIt() throws Exception {
        // Arrange
        String owner = createAccount("dono@example.com");
        String companyId = JsonPath.read(registerCompany(owner, CNPJ)
                .andReturn().getResponse().getContentAsString(), "$.companyId");
        String outsider = createAccount("outro@example.com");

        // Act / Assert
        registerCompany(outsider, CNPJ)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("CNPJ already registered"));
        mockMvc.perform(authorized(get("/v1/users/me"), outsider))
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.companyAccess").value(nullValue()));
        mockMvc.perform(authorized(get("/v1/companies/" + companyId + "/members"), outsider))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company_memberships m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                Long.class, "outro@example.com")).isZero();
        assertThat(count("companies")).isEqualTo(1);
    }

    @Test
    void registeringACompany_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/onboarding/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(CNPJ)))
                .andExpect(status().isUnauthorized());

        assertThat(count("companies")).isZero();
    }

    @Test
    void administrator_cannotRegisterACompanyNorReachCompanyData() throws Exception {
        // Arrange
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdministratorEntity admin = administratorRepository.save(AdministratorEntity.builder()
                .fullName("Admin V-Stable").email("admin@vstable.test").passwordHash("h").passwordSalt("s")
                .createdAt(now).updatedAt(now).build());
        String token = jwtTokenUtils.generateToken(admin);

        // Act / Assert
        registerCompany(token, CNPJ).andExpect(status().isForbidden());
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        assertThat(count("companies")).isZero();
    }

    @Test
    void removedMember_cannotRecoverTheOldCompanyWithItsToken_evenAfterRegisteringANewCompany() throws Exception {
        // Arrange: membro removido da empresa A que guarda o token emitido antes da remoção.
        String owner = createAccount("dono@example.com");
        String companyA = JsonPath.read(registerCompany(owner, CNPJ)
                .andReturn().getResponse().getContentAsString(), "$.companyId");
        String memberToken = createAccount("membro@example.com");
        jdbcTemplate.update("""
                INSERT INTO company_memberships (company_id, user_id, role, status, removed_at)
                SELECT ?::uuid, id, 'OPERATOR', 'REMOVED', now() FROM users WHERE email = ?
                """, companyA, "membro@example.com");
        mockMvc.perform(authorized(get("/v1/balance"), memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        // Act
        String companyB = JsonPath.read(registerCompany(memberToken, OTHER_CNPJ)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.companyId");

        // Assert: o mesmo token alcança só a empresa nova; a antiga continua negada.
        mockMvc.perform(authorized(get("/v1/users/me"), memberToken))
                .andExpect(jsonPath("$.companyId").value(companyB))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT role || ':' || status FROM company_memberships m JOIN users u ON u.id = m.user_id
                WHERE u.email = ? AND m.company_id = ?::uuid
                """, String.class, "membro@example.com", companyA)).isEqualTo("OPERATOR:REMOVED");
    }

    @Test
    void anEmailCanOnlyHaveOneAccount() throws Exception {
        createAccount("joao@example.com");

        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("joao@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void concurrentCompaniesForTheSameUser_onlyOneIsCreated() throws Exception {
        // Arrange: a linha do usuário fica bloqueada até as duas requisições esperarem por ela.
        String token = createAccount("joao@example.com");
        UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, "joao@example.com");

        // Act
        List<MvcResult> results = raceBehind(
                "SELECT id FROM users WHERE id = '" + userId + "' FOR UPDATE", "update users%",
                companyRequest(token, CNPJ), companyRequest(token, OTHER_CNPJ));

        // Assert
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
                .allSatisfy(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("USER_ALREADY_IN_COMPANY"));
        assertThat(count("companies")).isEqualTo(1);
        assertThat(count("company_memberships")).isEqualTo(1);
        assertThat(count("avenia_kyc_verifications")).isEqualTo(1);
    }

    @Test
    void concurrentCompaniesWithTheSameCnpj_onlyOneIsCreated() throws Exception {
        // Arrange: um CNPJ ainda não confirmado segura as duas inserções até ambas esperarem por ele.
        String first = createAccount("primeiro@example.com");
        String second = createAccount("segundo@example.com");

        // Act
        List<MvcResult> results = raceBehind(
                "INSERT INTO companies (id, legal_name, cnpj) VALUES (gen_random_uuid(), 'Bloqueio', '"
                        + NORMALIZED_CNPJ + "')",
                "insert into companies%",
                companyRequest(first, CNPJ), companyRequest(second, CNPJ));

        // Assert
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
                .allSatisfy(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("CNPJ already registered"));
        assertThat(count("companies")).isEqualTo(1);
        assertThat(count("company_memberships")).isEqualTo(1);
    }

    @Test
    void concurrentAccountsWithTheSameEmail_onlyOneIsCreated() throws Exception {
        // Act: um e-mail ainda não confirmado segura as duas inserções até ambas esperarem por ele.
        List<MvcResult> results = raceBehind(
                "INSERT INTO users (id, full_name, email, password_hash, password_salt) "
                        + "VALUES (gen_random_uuid(), 'Bloqueio', 'joao@example.com', 'h', 's')",
                "insert into users%",
                accountRequest("joao@example.com"), accountRequest("joao@example.com"));

        // Assert
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void singleStepOnboarding_keepsItsContractAndCreatesTheOwner() throws Exception {
        String body = mockMvc.perform(post("/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleStepJson("joao@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.companyId").isString())
                .andExpect(jsonPath("$.kycVerificationId").isString())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.twoFactorSetupRequired").value(false))
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.accessToken");
        mockMvc.perform(authorized(get("/v1/users/me"), token))
                .andExpect(jsonPath("$.companyId").value((String) JsonPath.read(body, "$.companyId")))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"));
        registerCompany(token, OTHER_CNPJ)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_IN_COMPANY"));
    }

    @Test
    void singleStepOnboarding_existingCnpj_isStillAConflictAndCreatesNoAccount() throws Exception {
        String owner = createAccount("dono@example.com");
        registerCompany(owner, CNPJ).andExpect(status().isCreated());

        mockMvc.perform(post("/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleStepJson("novo@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("CNPJ already registered"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, "novo@example.com")).isZero();
    }

    /**
     * Segura um bloqueio do banco numa conexão própria, dispara as requisições e só o libera quando todas
     * estão esperando por ele, o que garante a disputa sem depender de tempo. O bloqueio é desfeito ao final.
     */
    private List<MvcResult> raceBehind(String blockingSql, String waitingStatementPattern,
                                       MockHttpServletRequestBuilder... requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.length);
        try {
            List<Future<MvcResult>> futures;
            try (Connection lockHolder = dataSource.getConnection()) {
                lockHolder.setAutoCommit(false);
                try (Statement statement = lockHolder.createStatement()) {
                    statement.execute(blockingSql);
                }
                futures = Arrays.stream(requests)
                        .map(request -> executor.submit(() -> mockMvc.perform(request).andReturn()))
                        .toList();
                awaitRequestsWaitingForLock(waitingStatementPattern, requests.length);
                lockHolder.rollback();
            }
            return futures.stream().map(AccountAndCompanyRegistrationIntegrationTest::resultOf).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitRequestsWaitingForLock(String statementPattern, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (countRequestsWaitingForLock(statementPattern) < expected) {
            if (System.nanoTime() > deadline) {
                fail("As requisições concorrentes não chegaram ao bloqueio a tempo.");
            }
            Thread.onSpinWait();
        }
    }

    private int countRequestsWaitingForLock(String statementPattern) {
        Integer waiting = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE ?
                """, Integer.class, statementPattern);
        return waiting == null ? 0 : waiting;
    }

    private static MvcResult resultOf(Future<MvcResult> future) {
        try {
            return future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("Requisição concorrente não terminou", ex);
        }
    }

    private String createAccount(String email) throws Exception {
        String body = mockMvc.perform(accountRequest(email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.kycVerificationId").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions registerCompany(String token, String cnpj) throws Exception {
        return mockMvc.perform(companyRequest(token, cnpj));
    }

    private static MockHttpServletRequestBuilder accountRequest(String email) {
        return post("/v1/onboarding/account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(accountJson(email));
    }

    private static MockHttpServletRequestBuilder companyRequest(String token, String cnpj) {
        return authorized(post("/v1/onboarding/company"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyJson(cnpj));
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String accountJson(String email) {
        return """
                {"fullName":"Joao da Silva","email":"%s","password":"%s","confirmPassword":"%s"}
                """.formatted(email, PASSWORD, PASSWORD);
    }

    private static String companyJson(String cnpj) {
        return """
                {"legalName":"Empresa Exemplo Ltda","cnpj":"%s","country":"Brasil","zipCode":"90000-000",
                 "city":"Porto Alegre","state":"RS"}
                """.formatted(cnpj);
    }

    /** O JSON plano do cadastro em uma etapa, como o frontend envia hoje. */
    private static String singleStepJson(String email) {
        return """
                {"fullName":"Joao da Silva","email":"%s","password":"%s","confirmPassword":"%s",
                 "legalName":"Empresa Exemplo Ltda","cnpj":"%s","country":"Brasil","zipCode":"90000-000",
                 "city":"Porto Alegre","state":"RS"}
                """.formatted(email, PASSWORD, PASSWORD, CNPJ);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
