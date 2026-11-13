package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.transfer.BlindPayExecutionProcessor;
import ages.vstable.backend.support.ProviderClientFixture;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * cicd#85: API real, filtro JWT real e PostgreSQL efêmero. Asserções expressam
 * o comportamento seguro esperado; a tarefa opt-in pode falhar por vulnerabilidades.
 * Clientes externos e a submissão ao provedor são substituídos por mocks.
 */
@Tag("security-assessment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"transfers.reconciliation.enabled=false", "logging.level.root=WARN",
                "server.address=127.0.0.1", "server.tomcat.max-swallow-size=8MB"})
@AutoConfigureMockMvc
@Testcontainers
class ApplicationSecurityAssessmentTest {
    private static final UUID PATH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] WEBHOOK_KEY = Jwts.SIG.HS256.key().build().getEncoded();
    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "POST /v1/auth/login", "POST /v1/auth/login/verify-2fa",
            "POST /v1/onboarding", "POST /v1/webhooks/blindpay");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("blindpay.webhook-secret", () -> "whsec_" + Base64.getEncoder().encodeToString(WEBHOOK_KEY));
        registry.add("blindpay.instance-id", () -> "assessment-instance");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private AdministratorRepository administrators;
    @Autowired private JwtTokenUtils jwt;
    @Autowired private PasswordEncoder passwordEncoder;
    @Value("${local.server.port}") private int serverPort;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @Value("${api.auth.jwt.secret}") private String signingSecret;
    @MockitoBean private AveniaClient avenia;
    @MockitoBean private BlindPayClient blindpay;
    @MockitoBean private BlindPayExecutionProcessor processor;

    private UUID companyA;
    private UUID companyB;
    private String companyBCnpj;
    private UserEntity owner;
    private String ownerToken;
    private String password;
    private String passwordHash;
    private boolean exercisesMockProvider;
    private final java.util.Map<UUID, Set<String>> issuedTotpCodes = new java.util.HashMap<>();

    @BeforeEach
    void syntheticCompaniesAndOwner() {
        exercisesMockProvider = false;
        issuedTotpCodes.clear();
        // A URL é obrigatoriamente a do container desta classe, nunca a de um banco existente.
        jdbc.execute("TRUNCATE companies, administrators, blindpay_webhook_events CASCADE");
        password = UUID.randomUUID() + "aA1!";
        passwordHash = passwordEncoder.encode(password);
        companyA = company(syntheticCnpj());
        companyBCnpj = syntheticCnpj();
        companyB = company(companyBCnpj);
        owner = user(companyA);
        ownerToken = jwt.generateToken(owner);
    }

    @AfterEach
    void noExternalBusinessTraffic() {
        if (!exercisesMockProvider) {
            verifyNoInteractions(avenia, blindpay);
        }
    }

    @TestFactory
    List<DynamicTest> inventoryAndAccessChecks() throws Exception {
        List<Route> routes = routes();
        assertThat(routes).isNotEmpty();
        Path inventory = Path.of("build/reports/security-assessment/endpoints.csv");
        Files.createDirectories(inventory.getParent());
        List<String> rows = new ArrayList<>();
        rows.add("method,path,controller,handler,public,admin_only");
        for (Route route : routes) {
            rows.add(String.join(",", route.method(), route.path(), route.controller(), route.handler(),
                    String.valueOf(route.isPublic()), String.valueOf(route.adminOnly())));
        }
        Files.write(inventory, rows, StandardCharsets.UTF_8);
        writeConditionalJobInventory(inventory.resolveSibling("endpoints-perfis.csv"));

        String challenge = jwt.generateChallengeToken(owner);
        List<DynamicTest> cases = new ArrayList<>();
        for (Route route : routes) {
            if (!route.isPublic()) {
                cases.add(DynamicTest.dynamicTest("TC-API-01 anonymous " + route.key(), () ->
                        mvc.perform(routeRequest(route)).andExpect(status().isUnauthorized())));
                cases.add(DynamicTest.dynamicTest("TC-AUTH-05 challenge " + route.key(), () ->
                        mvc.perform(authorized(routeRequest(route), challenge)).andExpect(status().isUnauthorized())));
            }
            // OWNER/OPERATOR/VIEWER em rotas administrativas: somente LC-AUTHZ-01.
        }
        return cases;
    }

    @ParameterizedTest(name = "TC-RBAC-01 ADMIN nao decide compliance {0}")
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void adminCannotManuallyDecideCompliance(String decision) throws Exception {
        var before = jdbc.queryForMap("SELECT kyb_status, aml_status FROM companies WHERE id = ?", companyB);
        MvcResult result = mvc.perform(authorized(patch("/v1/companies/" + companyB + "/compliance-status"),
                        adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statusKyb\":\"%s\",\"statusAml\":\"%s\"}".formatted(decision, decision)))
                .andReturn();
        org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
            check.assertThat(result.getResponse().getStatus()).as("ADMIN nao pode decidir compliance").isEqualTo(403);
            check.assertThat(jdbc.queryForMap("SELECT kyb_status, aml_status FROM companies WHERE id = ?", companyB))
                    .as("Decisao manual nao deve alterar o estado persistido").isEqualTo(before);
        });
    }

    @Test
    @DisplayName("TC-RBAC-02 | ADMIN pode excluir empresa sintetica sem dependencias")
    void adminCanDeleteCompany() throws Exception {
        mvc.perform(authorized(delete("/v1/companies/" + companyB), adminToken()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE id = ?", Integer.class, companyB)).isZero();
    }

    @Test
    @DisplayName("TC-RBAC-03 | ADMIN pode editar cadastro empresarial")
    void adminCanEditCompanyRegistration() throws Exception {
        MvcResult result = mvc.perform(authorized(put("/v1/companies/" + companyB), adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"legalName":"Empresa Sintetica Atualizada", "cnpj":"%s",
                                 "country":"Brasil", "zipCode":"00000000", "state":"RS", "city":"Teste"}
                                """.formatted(companyBCnpj))).andReturn();
        assertThat(result.getResponse().getStatus()).as("HTTP para editar cadastro: ADMIN").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT legal_name FROM companies WHERE id = ?", String.class, companyB))
                .isEqualTo("Empresa Sintetica Atualizada");
    }

    @ParameterizedTest(name = "TC-RBAC-04 consulta e isolamento {0}")
    @EnumSource(CompanyRole.class)
    void operationalProfilesCanReadOwnCompanyOnly(CompanyRole role) throws Exception {
        String token = operationalToken(role);
        mvc.perform(authorized(get("/v1/companies/" + companyA), token)).andExpect(status().isOk());
        mvc.perform(authorized(get("/v1/companies/" + companyA + "/beneficiaries"), token))
                .andExpect(status().isOk());
        mvc.perform(authorized(get("/v1/companies/" + companyB), token)).andExpect(status().isForbidden());
        mvc.perform(authorized(get("/v1/companies/" + companyB + "/beneficiaries"), token))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "TC-RBAC-05 operacao de transferencia {0}")
    @EnumSource(value = CompanyRole.class, names = {"OWNER", "OPERATOR"})
    void operationalProfilesCanConfirmOwnCompanyOffer(CompanyRole role) throws Exception {
        String token = operationalToken(role);
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        mvc.perform(authorized(post(confirmPath(offer)), token).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation("100", "99"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isEqualTo(1);
        // Fixture envia segundo fator valido; sua exigencia e avaliada em TC-FIN-01.
    }

    @ParameterizedTest(name = "TC-RBAC-06 usuario nao decide compliance {0} {1}")
    @CsvSource({"OWNER, APPROVED", "OWNER, REJECTED", "OPERATOR, APPROVED", "OPERATOR, REJECTED",
            "VIEWER, APPROVED", "VIEWER, REJECTED"})
    void operationalProfilesCannotManuallyDecideCompliance(CompanyRole role, String decision) throws Exception {
        String token = operationalToken(role);
        var before = jdbc.queryForMap("SELECT kyb_status, aml_status FROM companies WHERE id = ?", companyA);
        mvc.perform(authorized(patch("/v1/companies/" + companyA + "/compliance-status"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statusKyb\":\"%s\",\"statusAml\":\"%s\"}".formatted(decision, decision)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForMap("SELECT kyb_status, aml_status FROM companies WHERE id = ?", companyA))
                .isEqualTo(before);
    }

    @ParameterizedTest(name = "TC-RBAC-07 permissoes do perfil empresarial {0}")
    @EnumSource(CompanyRole.class)
    void companyProfilesAdvertiseTheirOwnPermissions(CompanyRole role) throws Exception {
        MvcResult result = mvc.perform(authorized(get("/v1/users/me"), operationalToken(role)))
                .andExpect(status().isOk()).andReturn();
        var body = new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accountType").asString()).isEqualTo("USER");
        assertThat(body.get("companyAccess").get("role").asString()).isEqualTo(role.name());
        Set<String> actual = new java.util.HashSet<>();
        body.get("companyAccess").get("permissions").forEach(value -> actual.add(value.asString()));
        Set<String> expected = new java.util.HashSet<>(Set.of("COMPANY_READ", "BALANCE_READ",
                "BENEFICIARY_READ", "TRANSFER_READ", "SELF_MEMBERSHIP_READ"));
        if (role != CompanyRole.VIEWER) {
            expected.addAll(Set.of("BENEFICIARY_WRITE", "TRANSFER_CREATE"));
        }
        if (role == CompanyRole.OWNER) {
            expected.addAll(Set.of("MEMBERS_READ", "MEMBERS_MANAGE"));
        }
        assertThat(actual).as("Permissoes empresariais de " + role).isEqualTo(expected);
    }

    @Test
    @DisplayName("TC-RBAC-08 | VIEWER nao pode confirmar transferencia nem cadastrar beneficiario")
    void viewerCannotPerformWriteOperations() throws Exception {
        String token = operationalToken(CompanyRole.VIEWER);
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        int before = jdbc.queryForObject("SELECT count(*) FROM beneficiaries", Integer.class);
        mvc.perform(authorized(post(confirmPath(offer)), token).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation("100", "99"))).andExpect(status().isForbidden());
        mvc.perform(authorized(post("/v1/companies/" + companyA + "/beneficiaries"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"beneficiaryType":"COMPANY", "legalName":"Beneficiario Sintetico",
                                 "identificationDocument":"SYNTHETIC-ONLY", "country":"Brasil",
                                 "address":"Endereco Sintetico", "receivingMethod":"CRYPTO_WALLET",
                                 "walletAddress":"0x1111111111111111111111111111111111111111",
                                 "blockchainNetwork":"polygon", "confirmed":true}
                                """))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM beneficiaries", Integer.class)).isEqualTo(before);
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("TC-RBAC-09 | ADMIN e conta da plataforma sem vinculo empresarial")
    void adminIsASeparatePlatformAccount() throws Exception {
        MvcResult result = mvc.perform(authorized(get("/v1/users/me"), adminToken()))
                .andExpect(status().isOk()).andReturn();
        var body = new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accountType").asString()).isEqualTo("ADMIN");
        List<String> roles = new ArrayList<>();
        body.get("roles").forEach(value -> roles.add(value.asString()));
        assertThat(roles).containsExactly("ROLE_ADMIN");
        assertThat(body.get("companyAccess") == null || body.get("companyAccess").isNull()).isTrue();
        assertThat(body.get("companyId") == null || body.get("companyId").isNull()).isTrue();
    }

    private String operationalToken(CompanyRole role) {
        jdbc.update("UPDATE company_memberships SET role = ? WHERE user_id = ?", role.name(), owner.getId());
        return jwt.generateToken(owner);
    }

    @ParameterizedTest(name = "TC-FIN-01 sem TOTP com 2FA ativo={0}")
    @CsvSource({"false,422", "true,401"})
    void offerConfirmationMustRequireTotp(boolean enabled, int expectedStatus) throws Exception {
        if (enabled) enableTwoFactor(owner);
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        MvcResult result = mvc.perform(authorized(post(confirmPath(offer)), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(confirmationWithoutTotp("100", "99"))).andReturn();
        org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
            check.assertThat(result.getResponse().getStatus()).as("Confirmacao sem TOTP")
                    .isEqualTo(expectedStatus);
            check.assertThatCode(() -> verifyNoInteractions(processor)).doesNotThrowAnyException();
            check.assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isZero();
        });
    }

    @Test
    @DisplayName("TC-AUTHZ-02 | proprietario nao pode confirmar oferta de outra empresa")
    void cannotConfirmAnotherCompanyOffer() throws Exception {
        UserEntity other = user(companyB);
        UUID offer = offer(companyB, other.getId(), beneficiary(companyB));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation("100", "99"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isZero();
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("TC-AUTHZ-03 | oferta nao pode utilizar beneficiario de outra empresa")
    void cannotExecuteAnOfferWhoseBeneficiaryBelongsToAnotherCompany() throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyB));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation("100", "99"))).andExpect(status().isNotFound());
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("TC-FIN-02 | oferta expirada nao deve iniciar processamento")
    void expiredOfferIsRejected() throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        jdbc.update("UPDATE provider_quotes SET expires_at = now() - interval '1 second' WHERE id = ?", offer);
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation("100", "99"))).andExpect(status().is(422));
        verifyNoInteractions(processor);
    }

    @ParameterizedTest(name = "TC-FIN-03 valor adulterado {0}")
    @ValueSource(strings = {"99.99", "100.01", "100.00000001", "99999999999999999999999"})
    void changedAmountsAreRejected(String amount) throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation(amount, "99"))).andExpect(status().is(422));
        verifyNoInteractions(processor);
    }

    @ParameterizedTest(name = "TC-API-02 valor invalido {0}")
    @ValueSource(strings = {"0", "-1", "0.001", "null"})
    void invalidAmountsAreRejectedAtHttpBoundary(String amount) throws Exception {
        mvc.perform(authorized(post(confirmPath(PATH_ID)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation(amount, "99"))).andExpect(status().isBadRequest());
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("TC-CONC-02 | cinco confirmacoes simultaneas criam uma unica execucao")
    void fiveConcurrentConfirmationsAreIdempotentInPostgres() throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        // Cinco fatores independentes da mesma empresa: a corrida de negocio nao
        // depende de permitir replay do mesmo TOTP (avaliado em TC-CONC-01).
        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UserEntity actor = user(companyA, CompanyRole.OPERATOR);
            requests.add(authorized(post(confirmPath(offer)), jwt.generateToken(actor))
                    .contentType(MediaType.APPLICATION_JSON).content(confirmation(actor, "100", "99")));
        }
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = IntStream.range(0, 5).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(requests.get(index)).andReturn();
            })).toList();
            try {
                assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<String> executionIds = new ArrayList<>();
            for (var future : futures) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                executionIds.add(new tools.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).get("executionId").asString());
            }
            assertThat(executionIds).hasSize(5).containsOnly(executionIds.getFirst());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM base_transactions", Integer.class)).isEqualTo(1);
        verify(processor, times(1)).start(any());
    }

    @Test
    @DisplayName("TC-AUTH-06 | JWT expirado adulterado ou sem assinatura nao autentica")
    void rejectsExpiredTamperedAndUnsignedTokens() throws Exception {
        String expired = Jwts.builder().subject(owner.getEmail()).claim("account_type", "USER")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
        String[] parts = ownerToken.split("\\.");
        char first = parts[2].charAt(0);
        String tampered = parts[0] + "." + parts[1] + "." + (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        for (String token : List.of(expired, tampered, parts[0] + "." + parts[1] + ".")) {
            mvc.perform(authorized(get("/v1/users/me"), token)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("TC-AUTH-07 | desativacao da conta deve invalidar o acesso com JWT existente")
    void disabledAccountLosesAccessWithExistingToken() throws Exception {
        mvc.perform(authorized(get("/v1/users/me"), ownerToken)).andExpect(status().isOk());
        jdbc.update("UPDATE users SET active = false WHERE id = ?", owner.getId());
        mvc.perform(authorized(get("/v1/users/me"), ownerToken)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-API-03 | identificador com tentativa de injecao deve ser rejeitado")
    void injectionInResourceIdDoesNotReachDatabaseQuery() throws Exception {
        mvc.perform(authorized(get("/v1/companies/{id}", "' OR 1=1 --"), ownerToken))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-AUTH-08 | login real emite JWT e recusa senha incorreta sem detalhes internos")
    void realLoginAndBadCredentials() throws Exception {
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.getEmail() + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        MvcResult rejected = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.getEmail() + "\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(rejected.getResponse().getContentAsString())
                .doesNotContain("stackTrace", "passwordHash", "java.lang", passwordHash);
    }

    @Test
    @DisplayName("TC-API-04 | cadastro sintetico persiste atomico e repeticao nao duplica empresa")
    void onboardingPersistsOnceAndRejectsDuplicate() throws Exception {
        String body = """
                {"fullName":"Pessoa Sintetica", "email":"cadastro@example.test",
                 "password":"%s", "confirmPassword":"%s", "legalName":"Cadastro Sintetico",
                 "cnpj":"%s", "country":"Brasil", "zipCode":"00000000", "state":"RS"}
                """.formatted(password, password, syntheticCnpj());
        mvc.perform(post("/v1/onboarding").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/onboarding").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'cadastro@example.test'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("TC-AUTHZ-04 | beneficiario de outra empresa nao pode ser consultado por caminho adulterado")
    void beneficiaryOwnershipAtHttpBoundary() throws Exception {
        UUID otherBeneficiary = beneficiary(companyB);
        mvc.perform(authorized(get("/v1/companies/" + companyB + "/beneficiaries/" + otherBeneficiary), ownerToken))
                .andExpect(status().isForbidden());
        mvc.perform(authorized(get("/v1/companies/" + companyA + "/beneficiaries/" + otherBeneficiary), ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TC-AUTHZ-05 | historico detalhes execucao e comprovante nao expoem outra empresa")
    void realFinancialRecordsAreIsolated() throws Exception {
        UserEntity other = user(companyB);
        UUID otherOffer = offer(companyB, other.getId(), beneficiary(companyB));
        mvc.perform(authorized(post(confirmPath(otherOffer)), jwt.generateToken(other))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmation(other, "100", "99")))
                .andExpect(status().isOk());
        UUID executionId = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        UUID transactionId = jdbc.queryForObject("SELECT transaction_id FROM transfer_executions", UUID.class);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id, status) VALUES (?, 'APPROVED')", owner.getId());
        mvc.perform(authorized(get("/v1/transfer-executions/" + executionId), ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(authorized(get("/api/transferencias/" + transactionId), ownerToken)).andExpect(status().isNotFound());
        mvc.perform(authorized(get("/api/transferencias/" + transactionId + "/comprovante"), ownerToken))
                .andExpect(status().isNotFound());
        MvcResult history = mvc.perform(authorized(get("/api/transferencias").param("companyId", companyB.toString()),
                ownerToken)).andReturn();
        assertThat(history.getResponse().getStatus()).as("Negar empresa alheia ou filtrar pelo principal")
                .isIn(200, 403, 404);
        assertThat(history.getResponse().getContentAsString())
                .doesNotContain(transactionId.toString(), executionId.toString(), companyB.toString());
    }

    @Test
    @DisplayName("TC-AUTHZ-06 | upload KYC de outro representante e negado antes do provedor")
    void cannotStartAnotherUserComplianceUpload() throws Exception {
        UserEntity other = user(companyB);
        UUID kyc = UUID.randomUUID();
        jdbc.update("INSERT INTO avenia_kyc_verifications(id, user_id, status) VALUES (?, ?, 'PENDING')", kyc, other.getId());
        mvc.perform(authorized(post("/v1/onboarding/" + kyc + "/compliance/documento"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"PASSPORT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-INT-01 | webhook autentico repetido e deduplicado no PostgreSQL")
    void signedFinancialWebhookIsProcessedOnlyOnce() throws Exception {
        String body = "{\"webhook_event\":\"payin.complete\",\"id\":\"pi_synthetic\"}";
        long timestamp = Instant.now().getEpochSecond();
        mvc.perform(webhook("msg_synthetic", timestamp, body)).andExpect(status().isNoContent());
        mvc.perform(webhook("msg_synthetic", timestamp, body)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events", Integer.class)).isEqualTo(1);
        verify(processor, times(1)).onPayinEvent("pi_synthetic");
    }

    @Test
    @DisplayName("TC-INT-02 | webhook com corpo adulterado assinatura ausente ou timestamp antigo e rejeitado")
    void rejectsTamperedMissingAndOldWebhookSignatures() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        mvc.perform(webhook("msg_tamper", timestamp, "{}").content("{\"tampered\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/webhooks/blindpay").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(webhook("msg_old", timestamp - 301, "{}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events", Integer.class)).isZero();
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("TC-INT-03 | evento de compliance fora de ordem nao regride estado persistido")
    void olderComplianceWebhookDoesNotRegressPersistedState() throws Exception {
        jdbc.update("""
                INSERT INTO provider_accounts(company_id, provider, external_customer_id, status)
                VALUES (?, 'BLINDPAY', 're_synthetic', 'PENDING')
                """, companyA);
        long timestamp = Instant.now().getEpochSecond();
        mvc.perform(webhook("msg_new", timestamp, customerEvent(companyA, "approved", "2026-11-10T12:00:00Z")))
                .andExpect(status().isNoContent());
        mvc.perform(webhook("msg_older", timestamp, customerEvent(companyA, "rejected", "2026-11-10T11:00:00Z")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM provider_accounts", String.class)).isEqualTo("approved");
    }

    @Test
    @DisplayName("TC-INT-04 | webhook assinado com empresa divergente nao altera conta de compliance")
    void signedWebhookWithWrongCompanyDoesNotUpdateAccount() throws Exception {
        jdbc.update("""
                INSERT INTO provider_accounts(company_id, provider, external_customer_id, status)
                VALUES (?, 'BLINDPAY', 're_synthetic', 'PENDING')
                """, companyA);
        mvc.perform(webhook("msg_wrong_company", Instant.now().getEpochSecond(),
                        customerEvent(companyB, "approved", "2026-11-10T12:00:00Z")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM provider_accounts", String.class)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("TC-INT-05 | webhook autentico com JSON malformado nao e persistido")
    void signedMalformedWebhookDoesNotPersist() throws Exception {
        mvc.perform(webhook("msg_malformed", Instant.now().getEpochSecond(), "{not-json"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events", Integer.class)).isZero();
    }

    private MockHttpServletRequestBuilder webhook(String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        String signed = id + "." + timestamp + "." + body;
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        return post("/v1/webhooks/blindpay").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", id).header("svix-timestamp", timestamp).header("svix-signature", signature).content(body);
    }

    private String customerEvent(UUID company, String state, String updatedAt) {
        return """
                {"webhook_event":"customer.update", "id":"re_synthetic", "instance_id":"assessment-instance",
                 "external_id":"%s", "kyc_status":"%s", "aml_status":"approved", "updated_at":"%s"}
                """.formatted(company, state, updatedAt);
    }

    @Test
    @DisplayName("TC-AUTH-15 | troca de senha deve revogar a sessao emitida com a credencial anterior")
    void passwordChangeMustRevokeExistingSession() throws Exception {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(UUID.randomUUID().toString()), owner.getId());
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + owner.getEmail() + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(authorized(get("/v1/users/me"), ownerToken)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-AUTH-16 | TTL real da sessao e desafio expiram conforme contrato")
    void sessionTtlAndExpiredChallenge() throws Exception {
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(ownerToken).getPayload();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(18_000_000L);
        String expired = Jwts.builder().subject(owner.getEmail()).claim("account_type", "USER")
                .claim("purpose", "2FA_CHALLENGE").expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
        mvc.perform(post("/v1/auth/login/verify-2fa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\":\"" + expired + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "TC-FIN-13 persistencia fiat {0}")
    @CsvSource({"USD, 100.01, 99.01", "EUR, 101.23, 97.45", "BRL, 199.99, 192.38"})
    void confirmedAmountsMatchExecutionAndTransaction(String currency, String source, String target) throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        jdbc.update("UPDATE quote_requests SET target_currency = ? WHERE id = (SELECT quote_request_id FROM provider_quotes WHERE id = ?)", currency, offer);
        jdbc.update("UPDATE provider_quotes SET source_amount = ?, target_amount = ? WHERE id = ?", new BigDecimal(source), new BigDecimal(target), offer);
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation(source, target))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT source_amount FROM transfer_executions", BigDecimal.class)).isEqualByComparingTo(source);
        assertThat(jdbc.queryForObject("SELECT target_amount FROM transfer_executions", BigDecimal.class)).isEqualByComparingTo(target);
        assertThat(jdbc.queryForObject("SELECT foreign_amount FROM base_transactions", BigDecimal.class)).isEqualByComparingTo(target);
        assertThat(jdbc.queryForObject("SELECT foreign_currency FROM base_transactions", String.class)).isEqualTo(currency);
        assertThat(jdbc.queryForObject("SELECT beneficiary_id FROM transfer_executions", UUID.class))
                .isEqualTo(jdbc.queryForObject("SELECT beneficiary_id FROM import_transactions", UUID.class));
    }

    @ParameterizedTest(name = "TC-FIN-14 precisao cripto {0}")
    @CsvSource({"USDC, POLYGON, polygon, 19.12345678", "USDT, ETHEREUM, ethereum, 19.87654321", "BRLA, POLYGON, polygon, 99.12345678"})
    void cryptoQuoteMatchesValuesPersistedAfterSimulatedAveniaTicket(String currency, String network,
            String enumNetwork, String target) throws Exception {
        exercisesMockProvider = true;
        UUID beneficiary = beneficiary(companyA);
        jdbc.update("UPDATE beneficiaries SET receiving_method='CRYPTO_WALLET', currency=?, blockchain_network=?::blockchain_network_enum, avenia_wallet_id=?, avenia_sub_account_id='synthetic-sub' WHERE id=?",
                currency, enumNetwork, UUID.randomUUID(), beneficiary);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id, status, avenia_sub_account_id) VALUES (?, 'APPROVED', 'synthetic-sub')", owner.getId());
        UUID offer = offer(companyA, owner.getId(), beneficiary);
        jdbc.update("UPDATE quote_requests SET source_currency='BRL', target_currency=?, requested_amount=100 WHERE id=(SELECT quote_request_id FROM provider_quotes WHERE id=?)", currency, offer);
        jdbc.update("UPDATE provider_quotes SET provider='AVENIA', target_amount=? WHERE id=?", new BigDecimal(target), offer);
        when(avenia.createQuote(any())).thenReturn(new ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse(
                "synthetic-quote-token", "BRL", "PIX", new BigDecimal("100"), currency, network, new BigDecimal(target),
                BigDecimal.ONE, "BRL", false, false, List.of(), new BigDecimal("5"), "synthetic-pair", "IMMEDIATE"));
        when(avenia.createTicket(any())).thenReturn(new ages.vstable.backend.external.avenia.dto.AveniaTicketResponse(
                UUID.randomUUID(), null, OffsetDateTime.now().plusMinutes(5), "UNPAID", null, null));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100", target))).andExpect(status().isOk());
        verify(avenia, times(1)).createTicket(any());
        assertThat(jdbc.queryForObject("SELECT target_amount FROM transfer_executions", BigDecimal.class)).isEqualByComparingTo(target);
        assertThat(jdbc.queryForObject("SELECT foreign_amount FROM base_transactions", BigDecimal.class))
                .as("Montante persistido deve preservar o valor cripto confirmado e enviado ao provedor simulado")
                .isEqualByComparingTo(target);
    }

    @ParameterizedTest(name = "TC-FIN-15 estado terminal {0}")
    @EnumSource(value = ages.vstable.backend.entity.enums.TransferExecutionStatus.class, names = {"COMPLETED", "FAILED", "REFUNDED"})
    void terminalOperationsCannotBeReopened(ages.vstable.backend.entity.enums.TransferExecutionStatus terminal) throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100", "99"))).andExpect(status().isOk());
        UUID execution = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        jdbc.update("UPDATE transfer_executions SET status=? WHERE id=?", terminal.name(), execution);
        String before = jdbc.queryForObject("SELECT row_to_json(t)::text FROM transfer_executions t WHERE id=?", String.class, execution);
        var real = realProcessor(applicationContext.getBean(ages.vstable.backend.service.quote.BlindPayProvisioningService.class));
        real.start(execution);
        real.reconcile(execution);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> real.acceptRequote(execution, companyA,
                new BigDecimal("100"), new BigDecimal("99"))).isInstanceOf(ages.vstable.backend.exception.ConflictException.class);
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100", "99"))).andExpect(status().isOk());
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("101", "99"))).andExpect(status().is(422));
        assertThat(jdbc.queryForObject("SELECT row_to_json(t)::text FROM transfer_executions t WHERE id=?", String.class, execution)).isEqualTo(before);
        verify(processor, times(1)).start(any());
    }

    @ParameterizedTest(name = "TC-FIN-20 recotacao sem TOTP com 2FA ativo={0}")
    @CsvSource({"false,422", "true,401"})
    void requoteMustRequireTotpBeforeSimulatedSettlement(boolean enabled, int expectedStatus) throws Exception {
        exercisesMockProvider = true;
        UUID execution = prepareRequote();
        owner.setTwoFactorEnabled(enabled);
        users.saveAndFlush(owner);
        var real = stubbedRequoteProcessor("synthetic-payout", () -> { });
        org.mockito.Mockito.doAnswer(call -> {
            real.acceptRequote(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            return null;
        }).when(processor).acceptRequote(any(), any(), any(), any());
        MvcResult result = mvc.perform(authorized(post("/v1/transfer-executions/" + execution + "/requote/confirm"), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(confirmationWithoutTotp("100", "98"))).andReturn();
        org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
            check.assertThat(result.getResponse().getStatus()).as("Recotacao sem segundo fator")
                    .isEqualTo(expectedStatus);
            check.assertThatCode(() -> verifyNoInteractions(avenia, blindpay)).doesNotThrowAnyException();
            check.assertThatCode(() -> verify(processor, org.mockito.Mockito.never())
                    .acceptRequote(any(), any(), any(), any())).doesNotThrowAnyException();
            check.assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions WHERE id=?", String.class, execution))
                    .isEqualTo("REQUOTE_REQUIRED");
        });
    }

    private BlindPayExecutionProcessor stubbedRequoteProcessor(String payoutId, Runnable onProvisioning) {
        var provisioning = org.mockito.Mockito.mock(ages.vstable.backend.service.quote.BlindPayProvisioningService.class);
        when(provisioning.ensureManagedWallet(any(), any())).thenAnswer(call -> {
            onProvisioning.run();
            return new ages.vstable.backend.service.quote.BlindPayProvisioningService.ManagedWallet(
                    "synthetic-wallet", "0x" + "1".repeat(40));
        });
        var payout = org.mockito.Mockito.mock(ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse.class);
        when(payout.id()).thenReturn(payoutId);
        when(payout.status()).thenReturn("completed");
        when(blindpay.createEvmPayout(any(), any())).thenReturn(payout);
        return realProcessor(provisioning);
    }

    private BlindPayExecutionProcessor realProcessor(ages.vstable.backend.service.quote.BlindPayProvisioningService provisioning) {
        var context = applicationContext;
        return new BlindPayExecutionProcessor(
                context.getBean(ages.vstable.backend.repository.TransferExecutionRepository.class),
                context.getBean(ages.vstable.backend.repository.ProviderQuoteRepository.class),
                context.getBean(ages.vstable.backend.repository.CompanyRepository.class),
                context.getBean(ages.vstable.backend.repository.BeneficiaryRepository.class),
                context.getBean(ages.vstable.backend.repository.BaseTransactionRepository.class), blindpay, provisioning,
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @ParameterizedTest(name = "TC-API-13 multipart HTTP real {0}")
    @CsvSource({"0,400", "3145729,413", "4194305,413"})
    void multipartLimitsAreEnforcedByRealHttpServer(int size, int expectedStatus) {
        byte[] bytes = new byte[size];
        byte[] signature = "%PDF".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, bytes, 0, Math.min(size, signature.length));
        var resource = new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return "synthetic.pdf"; }
        };
        var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        parts.add("documentType", "INCORPORATION");
        parts.add("file", resource);
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        Integer status = org.springframework.web.client.RestClient.builder().requestFactory(factory).build().post()
                .uri("http://127.0.0.1:" + serverPort + "/v1/blindpay/compliance/documents")
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts).exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(expectedStatus);
    }

    @Autowired private org.springframework.context.ApplicationContext applicationContext;

    /** Every runtime controller operation gets a bounded input/error contract case. */
    @TestFactory
    List<DynamicTest> endpointInputAndErrorContracts() {
        return routes().stream().map(route -> DynamicTest.dynamicTest("LC-API-01 " + route.key(), () -> {
            // JUnit nao executa BeforeEach/AfterEach por DynamicTest.
            org.mockito.Mockito.reset(avenia, blindpay, processor);
            syntheticCompaniesAndOwner();
            boolean terms = route.path().equals("/v1/blindpay/compliance/terms");
            if(terms) {
                exercisesMockProvider=true;
                when(blindpay.initiateTermsOfService(any())).thenReturn(
                        new ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse("https://provider.example.test/terms"));
            }
            // Malformed JSON cannot reach a financial/provider write. GET/DELETE UUIDs are invalid.
            String path = route.path().replaceAll("\\{[^}]+}", "invalid-uuid");
            var call = request(HttpMethod.valueOf(route.method()), path)
                    .contentType(MediaType.APPLICATION_JSON).content("{")
                    .param("livenessId", "synthetic");
            if (!route.isPublic()) authorized(call, route.adminOnly() ? adminToken() : ownerToken);
            try {
                MvcResult result = mvc.perform(call).andReturn();
                int code = result.getResponse().getStatus();
                // Leituras/setup sem parametros podem responder 2xx sem consumir o corpo.
                assertThat(code).as(route.key() + " must not crash on bounded invalid input")
                        .isBetween(200, 499);
                assertNoInternalData(result);
            } finally {
                if (terms) {
                    verify(blindpay).initiateTermsOfService(any());
                    org.mockito.Mockito.verifyNoMoreInteractions(avenia, blindpay);
                } else {
                    verifyNoInteractions(avenia, blindpay);
                }
            }
        })).toList();
    }

    @TestFactory
    List<DynamicTest> allBusinessProfilesAreDeniedAdministrativeFunctions() {
        List<DynamicTest> cases = new ArrayList<>();
        for (Route route : routes().stream().filter(Route::adminOnly).toList()) {
            for (CompanyRole role : CompanyRole.values()) {
                cases.add(DynamicTest.dynamicTest("LC-AUTHZ-01 " + role + " " + route.key(), () -> {
                    syntheticCompaniesAndOwner();
                    mvc.perform(authorized(routeRequest(route), operationalToken(role)))
                            .andExpect(status().isForbidden());
                }));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "LC-AUTHZ-02 KYC estrangeiro {0}")
    @ValueSource(strings = {"document-start", "document-submit", "liveness-start", "liveness-submit", "liveness-status", "kyc-submit"})
    void everyPersonalComplianceOperationEnforcesOwnership(String operation) throws Exception {
        UserEntity other = user(companyB);
        UUID kyc = UUID.randomUUID();
        jdbc.update("INSERT INTO avenia_kyc_verifications(id,user_id,status) VALUES (?,?,'PENDING')", kyc, other.getId());
        String root = "/v1/onboarding/" + kyc + "/compliance/";
        MockHttpServletRequestBuilder call = switch (operation) {
            case "document-start" -> post(root + "documento").content("{\"documentType\":\"PASSPORT\"}");
            case "document-submit" -> put(root + "documento").content("{\"documentoId\":\"synthetic-document\"}");
            case "liveness-start" -> post(root + "liveness");
            case "liveness-submit" -> put(root + "liveness").content("{\"livenessId\":\"synthetic-liveness\"}");
            case "liveness-status" -> get(root + "liveness/status").param("livenessId", "synthetic-liveness");
            default -> post(root + "kyc").content("""
                    {"fullName":"Synthetic Person","dateOfBirth":"1990-01-01","taxIdNumber":"SYNTHETIC",
                     "email":"kyc@example.test","phone":"SYNTHETIC","country":"Brasil","state":"RS",
                     "city":"Teste","zipCode":"00000000","streetAddress":"Synthetic"}
                    """);
        };
        mvc.perform(authorized(call.contentType(MediaType.APPLICATION_JSON), ownerToken)).andExpect(status().isForbidden());
        verifyNoInteractions(avenia, blindpay);
        assertThat(jdbc.queryForObject("SELECT document_id FROM avenia_kyc_verifications WHERE id=?", String.class, kyc)).isNull();
    }

    @ParameterizedTest(name = "LC-FIN-01 associacao alterada {0}")
    @ValueSource(strings = {"foreign-beneficiary", "moved-beneficiary", "unselected", "expired", "expiry-margin"})
    void financialAssociationAndTemporalGuardsPrecedeSubmission(String condition) throws Exception {
        UUID beneficiary = beneficiary(companyA);
        UUID offer = offer(companyA, owner.getId(), beneficiary);
        switch (condition) {
            case "foreign-beneficiary" -> jdbc.update("UPDATE quote_requests SET beneficiary_id=? WHERE id=(SELECT quote_request_id FROM provider_quotes WHERE id=?)",
                    beneficiary(companyB), offer);
            case "moved-beneficiary" -> jdbc.update("UPDATE beneficiaries SET company_id=? WHERE id=?", companyB, beneficiary);
            case "unselected" -> jdbc.update("UPDATE provider_quotes SET status='AVAILABLE' WHERE id=?", offer);
            case "expired" -> jdbc.update("UPDATE provider_quotes SET expires_at=now()-interval '1 second' WHERE id=?", offer);
            default -> jdbc.update("UPDATE provider_quotes SET expires_at=now()+interval '1 second' WHERE id=?", offer);
        }
        int expected = condition.contains("beneficiary") ? 404 : 422;
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100", "99"))).andExpect(status().is(expected));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_executions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM base_transactions", Integer.class)).isZero();
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("LC-FIN-02 | campos extras nao alteram proprietario moeda tarifa beneficiario ou estado")
    void massAssignmentCannotOverrideExecutionProperties() throws Exception {
        UUID beneficiary = beneficiary(companyA);
        UUID foreign = beneficiary(companyB);
        UUID offer = offer(companyA, owner.getId(), beneficiary);
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceAmount":100,"targetAmount":99,"companyId":"%s","beneficiaryId":"%s",
                     "requestedBy":"%s","sourceCurrency":"XXX","targetCurrency":"XXX",
                     "totalFee":-999,"status":"COMPLETED","provider":"AVENIA","totpCode":"%s"}
                    """.formatted(companyB, foreign, UUID.randomUUID(), validTotp(owner)))).andExpect(status().isOk());
        var row = jdbc.queryForMap("SELECT company_id,beneficiary_id,requested_by,source_currency,target_currency,total_fee,status FROM transfer_executions");
        assertThat(row.get("company_id")).isEqualTo(companyA);
        assertThat(row.get("beneficiary_id")).isEqualTo(beneficiary);
        assertThat(row.get("requested_by")).isEqualTo(owner.getId());
        assertThat(row.get("source_currency")).isEqualTo("USDB");
        assertThat(row.get("target_currency")).isEqualTo("USD");
        assertThat((BigDecimal) row.get("total_fee")).isEqualByComparingTo("1");
        assertThat(row.get("status").toString()).isEqualTo("PAYOUT_SUBMITTING");
    }

    @Test
    @DisplayName("LC-AUTHZ-03 | cadastro publico nao aceita admin empresa alheia ou compliance aprovado")
    void publicSignupDoesNotAllowPrivilegeOrOwnershipMassAssignment() throws Exception {
        String body = """
                {"fullName":"Synthetic","email":"property@example.test","password":"%s","confirmPassword":"%s",
                 "legalName":"Synthetic Signup","cnpj":"%s","country":"Brasil","zipCode":"00000000","state":"RS",
                 "companyId":"%s","role":"ADMIN","accountType":"ADMIN","accessLevel":"SUPER_ADMIN",
                 "kybStatus":"APPROVED","amlStatus":"APPROVED"}
                """.formatted(password,password,syntheticCnpj(),companyB);
        mvc.perform(post("/v1/onboarding").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        UUID actualCompany = jdbc.queryForObject("SELECT company_id FROM users WHERE email='property@example.test'", UUID.class);
        assertThat(actualCompany).isNotEqualTo(companyB);
        assertThat(jdbc.queryForObject("SELECT kyb_status FROM companies WHERE id=?", String.class, actualCompany)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM administrators", Integer.class)).isZero();
    }

    @ParameterizedTest(name = "LC-API-02 injecao em filtro {0}")
    @ValueSource(strings = {"' OR 1=1 --", "${jndi:ldap://127.0.0.1/synthetic}", "<script>synthetic</script>"})
    void filterInjectionDoesNotExpandCompanyScope(String payload) throws Exception {
        beneficiary(companyA); UUID foreign = beneficiary(companyB);
        MvcResult response = mvc.perform(authorized(get("/v1/companies/"+companyA+"/beneficiaries").param("search",payload),ownerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(foreign.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM beneficiaries",Integer.class)).isEqualTo(2);
        assertNoInternalData(response);
    }

    @Test
    @DisplayName("LC-API-03 | paginacao solicita volume extremo sem retornar acima do limite configurado")
    void paginationIsBoundedWithoutCreatingLoad() throws Exception {
        MvcResult result=mvc.perform(authorized(get("/v1/companies/"+companyA+"/beneficiaries")
                .param("size","2147483647"),ownerToken)).andExpect(status().isOk()).andReturn();
        var body=new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("pageable").get("pageSize").asInt()).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("LC-ABUSE-01 | dez logins incorretos preservam credenciais e nao criam sessoes")
    void boundedLoginRepetitionRejectsCredentialsWithoutServerErrors() throws Exception {
        for(int i=0;i<10;i++) {
            MvcResult result=mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\""+owner.getEmail()+"\",\"password\":\"synthetic-invalid\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(result.getResponse().getHeader("Authorization")).isNull();
            assertNoInternalData(result);
        }
        assertThat(users.findById(owner.getId()).orElseThrow().getPassword()).isEqualTo(passwordHash);
        // Sem limiar de negocio definido, nao se inventa um lockout obrigatorio apos dez tentativas.
        // Ausencia de limitador local e documentada pela revisao de configuracao, nao por esta assercao.
    }

    @ParameterizedTest(name = "LC-INT-01 timestamp webhook {0}")
    @ValueSource(longs={-601,601})
    void authenticWebhookOutsideTimeWindowIsRejected(long delta) throws Exception {
        mvc.perform(webhook("synthetic-expired",Instant.now().getEpochSecond()+delta,
                "{\"webhook_event\":\"payin.complete\",\"id\":\"synthetic-payin\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events",Integer.class)).isZero();
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("LC-INT-02 | cinco entregas concorrentes do mesmo webhook processam uma vez")
    void concurrentDuplicateWebhooksAreAtomic() throws Exception {
        long timestamp=Instant.now().getEpochSecond();
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(5)) {
            var work=IntStream.range(0,5).mapToObj(i->pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return mvc.perform(webhook("synthetic-concurrent",timestamp,
                        "{\"webhook_event\":\"payin.complete\",\"id\":\"synthetic-payin\"}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            for(var response:work) assertThat(response.get(15,TimeUnit.SECONDS)).isEqualTo(204);
        }
        verify(processor,times(1)).onPayinEvent("synthetic-payin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events",Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("LC-INT-03 | falha transacional no webhook permite uma retentativa valida")
    void webhookFailureDoesNotPoisonDeduplication() throws Exception {
        long stamp=Instant.now().getEpochSecond();
        String body="{\"webhook_event\":\"payin.complete\",\"id\":\"synthetic-retry\"}";
        when(processor.onPayinEvent("synthetic-retry")).thenThrow(new IllegalArgumentException("synthetic transient" )).thenReturn(true);
        mvc.perform(webhook("synthetic-retry",stamp,body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events",Integer.class)).isZero();
        mvc.perform(webhook("synthetic-retry",stamp,body)).andExpect(status().isNoContent());
        verify(processor,times(2)).onPayinEvent("synthetic-retry");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blindpay_webhook_events",Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("LC-CONC-01 | corrida entre confirmacao de recotacao e conciliacao usa uma chave de payout")
    void requoteAndReconciliationUseStableIdempotencyAndAmounts() throws Exception {
        exercisesMockProvider=true;
        UUID execution=prepareRequote();
        var real=stubbedRequoteProcessor("synthetic-once", () -> { });
        CountDownLatch gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(5)) {
            var work=IntStream.range(0,5).mapToObj(i->pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                if(i==0) real.acceptRequote(execution,companyA,new BigDecimal("100"),new BigDecimal("98"));
                else real.reconcile(execution);
                return true;
            })).toList(); gate.countDown();
            for(var item:work) assertThat(item.get(20,TimeUnit.SECONDS)).isTrue();
        }
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(blindpay,org.mockito.Mockito.atLeastOnce()).createEvmPayout(any(),keys.capture());
        assertThat(keys.getAllValues().stream().distinct().toList()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions WHERE id=?",String.class,execution)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM base_transactions",Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("LC-CONC-02 | expiracao durante provisionamento nao deve submeter cotacao vencida")
    void expiryBetweenValidationAndPayoutSubmissionIsCheckedAgain() throws Exception {
        exercisesMockProvider=true;
        UUID execution=prepareRequote();
        var current=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        java.time.Clock controlled=new java.time.Clock(){
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return current.get();}
        };
        // Simula provisionamento lento pelo relogio, sem sleep.
        var real=stubbedRequoteProcessor("synthetic-expired-payout", () -> current.set(current.get().plusSeconds(600)));
        org.springframework.test.util.ReflectionTestUtils.setField(real,"clock",controlled);
        real.acceptRequote(execution,companyA,new BigDecimal("100"),new BigDecimal("98"));
        verify(blindpay,org.mockito.Mockito.never()).createEvmPayout(any(),any());
    }

    private UUID prepareRequote() throws Exception {
        UUID offer=offer(companyA,owner.getId(),beneficiary(companyA));
        mvc.perform(authorized(post(confirmPath(offer)),ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100","99"))).andExpect(status().isOk());
        UUID execution=jdbc.queryForObject("SELECT id FROM transfer_executions",UUID.class);
        jdbc.update("UPDATE transfer_executions SET status='REQUOTE_REQUIRED',payout_quote_receiver_amount=98,payout_quote_expires_at=now()+interval '5 minutes' WHERE id=?",execution);
        return execution;
    }

    @Test
    @DisplayName("LC-FIN-03 | recotacao de outra empresa nao pode chegar ao processamento real")
    void foreignCompanyCannotAcceptRequoteInRealProcessor() throws Exception {
        UUID execution=prepareRequote();
        var real=realProcessor(applicationContext.getBean(ages.vstable.backend.service.quote.BlindPayProvisioningService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->real.acceptRequote(execution,companyB,new BigDecimal("100"),new BigDecimal("98")))
                .isInstanceOf(ages.vstable.backend.exception.NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions WHERE id=?",String.class,execution)).isEqualTo("REQUOTE_REQUIRED");
        verifyNoInteractions(avenia,blindpay);
    }

    @ParameterizedTest(name="LC-AUTHZ-04 beneficiario alheio na cotacao {0}")
    @ValueSource(strings={"/v1/quotes","/v1/transfers/quote"})
    void bothQuotePathsRejectForeignBeneficiaryBeforeCallingProviders(String path) throws Exception {
        UUID foreign=beneficiary(companyB);
        String body=path.equals("/v1/quotes")?"""
                {"beneficiaryId":"%s","direction":"PAYOUT","sourceCurrency":"BRL","targetCurrency":"USD",
                 "sourcePaymentMethod":"pix","targetPaymentMethod":"wire","amount":100,"amountSide":"SOURCE",
                 "token":"USDB","blockchainNetwork":"base_sepolia","coverFees":false}
                """.formatted(foreign):"""
                {"beneficiaryId":"%s","amount":100,"amountType":"SOURCE","sourceCurrency":"BRL",
                 "destinationCurrency":"USD","paymentMethod":"PIX"}
                """.formatted(foreign);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id,status) VALUES (?,'APPROVED')",owner.getId());
        mvc.perform(authorized(post(path),ownerToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        verifyNoInteractions(avenia,blindpay);
    }

    private void assertNoInternalData(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("passwordHash","password_hash","passwordSalt","password_salt",
                        "stackTrace","java.lang.","org.hibernate.",passwordHash,signingSecret);
    }

    @Test
    @DisplayName("LC-AUTH-01 | ativacao login com desafio uso unico e protecao de reconfiguracao 2FA")
    void fullTwoFactorLifecycleUsesRealHttpAndDatabase() throws Exception {
        mvc.perform(authorized(post("/v1/auth/2fa/setup"),ownerToken)).andExpect(status().isOk());
        String secret=users.findById(owner.getId()).orElseThrow().getTwoFactorSecret();
        assertThat(secret).isNotBlank();
        String stored=jdbc.queryForObject("SELECT two_factor_secret FROM users WHERE id=?",String.class,owner.getId());
        assertThat(stored).isNotEqualTo(secret); // encrypted at rest
        long window=Instant.now().getEpochSecond()/30;
        var totp=applicationContext.getBean(ages.vstable.backend.service.TotpService.class);
        byte[] key=org.springframework.test.util.ReflectionTestUtils.invokeMethod(totp,"decodeBase32",secret);
        String activation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(totp,"generateCodeForWindow",key,window);
        mvc.perform(authorized(post("/v1/auth/2fa/activate"),ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+activation+"\"}")).andExpect(status().isOk());
        MvcResult login=mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\""+owner.getEmail()+"\",\"password\":\""+password+"\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(login.getResponse().getHeader("Authorization")).isNull();
        String challenge=new tools.jackson.databind.ObjectMapper().readTree(login.getResponse().getContentAsString())
                .get("challengeToken").asString();
        String next=org.springframework.test.util.ReflectionTestUtils.invokeMethod(totp,"generateCodeForWindow",key,window+1);
        String body="{\"challengeToken\":\""+challenge+"\",\"code\":\""+next+"\"}";
        mvc.perform(post("/v1/auth/login/verify-2fa").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/auth/login/verify-2fa").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        MvcResult reset=mvc.perform(authorized(post("/v1/auth/2fa/setup"),ownerToken)).andReturn();
        assertThat(reset.getResponse().getStatus()).isIn(401,403);
        assertThat(users.findById(owner.getId()).orElseThrow().getTwoFactorSecret()).isEqualTo(secret);
    }

    @Test
    @DisplayName("LC-CONFIG-01 | CORS headers sessao stateless e executor limitado")
    void localSecurityConfigurationHasConcreteBoundaries() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/v1/users/me")
                .header("Origin","https://untrusted.example.test").header("Access-Control-Request-Method","GET"))
                .andExpect(status().isForbidden());
        MvcResult result=mvc.perform(authorized(get("/v1/users/me"),ownerToken)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getCookies()).noneMatch(cookie->cookie.getName().equals("JSESSIONID"));
        mvc.perform(get("/v1/users/me").param("token",ownerToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/me").cookie(new jakarta.servlet.http.Cookie("JSESSIONID","synthetic")))
                .andExpect(status().isUnauthorized());
        var executor=(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor)applicationContext.getBean("quoteProviderExecutor");
        assertThat(executor.getMaxPoolSize()).isEqualTo(8);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
    }

    @ParameterizedTest(name="LC-OUT-05 redirect com builder real do Spring {0}")
    @ValueSource(strings={"avenia","blindpay"})
    @org.junit.jupiter.api.Timeout(20)
    void configuredHttpClientsDoNotFollowCrossOriginRedirects(String provider) throws Exception {
        var reached=new java.util.concurrent.atomic.AtomicInteger();
        var sink=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var origin=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        sink.createContext("/",exchange->{
            reached.incrementAndGet(); byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); sink.start();
        origin.createContext("/",exchange->{
            exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+sink.getAddress().getPort()+"/synthetic");
            exchange.sendResponseHeaders(302,-1); exchange.close();
        }); origin.start();
        try {
            var builder=applicationContext.getBean(org.springframework.web.client.RestClient.Builder.class).clone();
            String base="http://127.0.0.1:"+origin.getAddress().getPort();
            try {
                ProviderClientFixture.call(provider, base, builder).run();
            } catch(ages.vstable.backend.exception.AveniaIntegrationException|ages.vstable.backend.exception.BlindPayIntegrationException expected) {
                // A recusa controlada e aceitavel; nao contactar a outra origem e o controle avaliado.
            }
            assertThat(reached.get()).as("Cliente configurado da aplicacao: nao seguir outra origem").isZero();
        } finally { origin.stop(0);sink.stop(0); }
    }

    private record Route(String method, String path, String controller, String handler, boolean adminOnly) {
        String key() { return method + " " + path; }
        boolean isPublic() { return PUBLIC_ROUTES.contains(key()); }
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().equals("ages.vstable.backend.controller")) {
                return;
            }
            PreAuthorize methodRule = handler.getMethodAnnotation(PreAuthorize.class);
            PreAuthorize classRule = handler.getBeanType().getAnnotation(PreAuthorize.class);
            boolean adminOnly = (methodRule != null && methodRule.value().contains("hasRole('ADMIN')"))
                    || (methodRule == null && classRule != null && classRule.value().contains("hasRole('ADMIN')"));
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String path : info.getPatternValues()) {
                    routes.add(new Route(method.name(), path, handler.getBeanType().getSimpleName(),
                            handler.getMethod().getName(), adminOnly));
                }
            }
        });
        return routes.stream().sorted(Comparator.comparing(Route::key)).toList();
    }

    private void writeConditionalJobInventory(Path path) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("method,path,controller,handler,profile,application_auth,scope");
        for (Class<?> controller : List.of(ages.vstable.backend.controller.SeedJobController.class,
                ages.vstable.backend.controller.MigrationJobController.class)) {
            String profile = String.join("|", controller.getAnnotation(org.springframework.context.annotation.Profile.class).value());
            for (var method : controller.getDeclaredMethods()) {
                var mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
                if (mapping == null) continue;
                for (String endpoint : mapping.value()) {
                    rows.add(String.join(",", "POST", endpoint, controller.getSimpleName(), method.getName(),
                            profile, "permitAll", "conditional-job-not-active-in-default-profile"));
                }
            }
        }
        Files.write(path, rows, StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder routeRequest(Route route) {
        String path = route.path().replaceAll("\\{[^}]+}", PATH_ID.toString());
        String body = (route.method().equals("POST") && route.path().equals("/v1/companies"))
                || (route.method().equals("PUT") && route.path().equals("/v1/companies/{id}"))
                ? """
                  {"legalName":"Empresa Sintetica", "cnpj":"%s", "country":"Brasil",
                   "zipCode":"00000000", "state":"RS"}
                  """.formatted(companyBCnpj) : "{}";
        return request(HttpMethod.valueOf(route.method()), path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private UUID company(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO companies(id, legal_name, cnpj) VALUES (?, 'Empresa Sintetica', ?)", id, cnpj);
        return id;
    }

    private String syntheticCnpj() {
        // Identificador gerado para esta massa, sem copiar cadastro de pessoa/empresa real.
        String digits = Long.toString(java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(100_000_000_000L, 1_000_000_000_000L));
        for (int[] weights : List.of(new int[]{5,4,3,2,9,8,7,6,5,4,3,2}, new int[]{6,5,4,3,2,9,8,7,6,5,4,3,2})) {
            int sum = 0;
            for (int i = 0; i < weights.length; i++) { sum += (digits.charAt(i) - '0') * weights[i]; }
            int remainder = sum % 11;
            digits += remainder < 2 ? 0 : 11 - remainder;
        }
        return digits;
    }

    private UserEntity user(UUID company) {
        return user(company, CompanyRole.OWNER);
    }

    private UserEntity user(UUID company, CompanyRole role) {
        UserEntity user = users.saveAndFlush(UserEntity.builder().companyId(company)
                .fullName("Pessoa Sintetica").email(UUID.randomUUID() + "@example.test")
                .passwordHash(passwordHash).passwordSalt("unused-synthetic-salt")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).updatedAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        jdbc.update("INSERT INTO company_memberships(company_id, user_id, role, status) VALUES (?, ?, ?, 'ACTIVE')",
                company, user.getId(), role.name());
        return user;
    }

    private String adminToken() {
        // O schema ainda exige access_level. Este valor legado e apenas uma fixture;
        // a regra de negocio revisada possui um unico papel global ADMIN.
        AdministratorEntity admin = administrators.saveAndFlush(AdministratorEntity.builder()
                .fullName("Administrador Sintetico").email(UUID.randomUUID() + "@example.test")
                .passwordHash("unused-synthetic-hash").passwordSalt("unused-synthetic-salt")
                .accessLevel(AdminAccessLevel.SUPER_ADMIN).createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return jwt.generateToken(admin);
    }

    private UUID beneficiary(UUID company) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO beneficiaries(id, company_id, nickname) VALUES (?, ?, 'Beneficiario Sintetico')", id, company);
        return id;
    }

    private UUID offer(UUID company, UUID user, UUID beneficiary) {
        UUID quote = UUID.randomUUID();
        UUID offer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quote_requests(id, company_id, requested_by, beneficiary_id, direction,
                  source_currency, target_currency, source_payment_method, target_payment_method,
                  requested_amount, amount_side, token, blockchain_network, status)
                VALUES (?, ?, ?, ?, 'PAYOUT', 'USDB', 'USD', 'wallet', 'wire', 100, 'SOURCE',
                        'USDB', 'base_sepolia', 'COMPLETED')
                """, quote, company, user, beneficiary);
        jdbc.update("""
                INSERT INTO provider_quotes(id, quote_request_id, provider, external_quote_id,
                  idempotency_key, status, source_amount, target_amount, exchange_rate, total_fee, expires_at)
                VALUES (?, ?, 'BLINDPAY', 'synthetic-quote', ?, 'SELECTED', 100, 99, 0.99, 1, now() + interval '5 minutes')
                """, offer, quote, "assessment:" + offer);
        return offer;
    }

    private String confirmPath(UUID offer) { return "/v1/quotes/offers/" + offer + "/confirm"; }

    private String confirmation(String source, String target) {
        return confirmation(owner, source, target);
    }

    private String confirmation(UserEntity actor, String source, String target) {
        return "{\"sourceAmount\":" + source + ",\"targetAmount\":" + target
                + ",\"totpCode\":\"" + validTotp(actor) + "\"}";
    }

    private void enableTwoFactor(UserEntity actor) {
        if (!Boolean.TRUE.equals(actor.getTwoFactorEnabled())) {
            actor.setTwoFactorSecret(applicationContext.getBean(ages.vstable.backend.service.TotpService.class).generateSecret());
            actor.setTwoFactorEnabled(true);
            users.saveAndFlush(actor);
        }
    }

    private String validTotp(UserEntity actor) {
        enableTwoFactor(actor);
        var service = applicationContext.getBean(ages.vstable.backend.service.TotpService.class);
        byte[] key = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "decodeBase32", actor.getTwoFactorSecret());
        Set<String> issued = issuedTotpCodes.computeIfAbsent(actor.getId(), ignored -> new java.util.HashSet<>());
        long window = Instant.now().getEpochSecond() / 30;
        // Janela atual +/-1, sem repetir codigo entre requisicoes da mesma conta.
        // O DTO atual ainda ignora totpCode; isto prepara os demais casos para a
        // correcao do contrato, sem fingir que a aplicacao ja exige o fator.
        for (long offset : new long[]{0, -1, 1}) {
            String code = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "generateCodeForWindow", key, window + offset);
            if (issued.add(code)) {
                assertThat(service.verifyCode(actor.getTwoFactorSecret(), code)).isTrue();
                return code;
            }
        }
        throw new IllegalStateException("Fixture esgotou codigos TOTP distintos na janela");
    }

    private String confirmationWithoutTotp(String source, String target) {
        return "{\"sourceAmount\":" + source + ",\"targetAmount\":" + target + "}";
    }
}
