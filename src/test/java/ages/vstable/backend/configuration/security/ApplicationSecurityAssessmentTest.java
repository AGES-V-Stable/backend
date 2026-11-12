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

    @BeforeEach
    void syntheticCompaniesAndOwner() {
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

        String challenge = jwt.generateChallengeToken(owner);
        List<DynamicTest> cases = new ArrayList<>();
        for (Route route : routes) {
            if (!route.isPublic()) {
                cases.add(DynamicTest.dynamicTest("TC-API-01 anonymous " + route.key(), () ->
                        mvc.perform(routeRequest(route)).andExpect(status().isUnauthorized())));
                cases.add(DynamicTest.dynamicTest("TC-AUTH-05 challenge " + route.key(), () ->
                        mvc.perform(authorized(routeRequest(route), challenge)).andExpect(status().isUnauthorized())));
            }
            if (route.adminOnly()) {
                cases.add(DynamicTest.dynamicTest("TC-AUTHZ-01 USER em rota ADMIN " + route.key(), () ->
                        mvc.perform(authorized(routeRequest(route), ownerToken)).andExpect(status().isForbidden())));
            }
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
        // Este caso avalia acesso por perfil; a ausencia de TOTP e avaliada separadamente em TC-FIN-01.
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

    @Test
    @DisplayName("TC-FIN-01 | confirmacao de oferta deve exigir segundo fator antes da submissao")
    void offerConfirmationMustRequireTotp() throws Exception {
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        MvcResult result = mvc.perform(authorized(post(confirmPath(offer)), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(confirmation("100", "99"))).andReturn();
        assertThat(result.getResponse().getStatus()).as("Confirmacao sem TOTP e com 2FA desativado")
                .isEqualTo(422);
        verifyNoInteractions(processor);
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
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = IntStream.range(0, 5).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(authorized(post(confirmPath(offer)), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmation("100", "99"))).andReturn();
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
                        .contentType(MediaType.APPLICATION_JSON).content(confirmation("100", "99")))
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
                ownerToken)).andExpect(status().isOk()).andReturn();
        assertThat(history.getResponse().getContentAsString()).doesNotContain(transactionId.toString());
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

    @Test
    @DisplayName("TC-FIN-20 | recotacao nao deve concluir payout sem TOTP")
    void requoteMustRequireTotpBeforeSimulatedSettlement() throws Exception {
        exercisesMockProvider = true;
        UUID offer = offer(companyA, owner.getId(), beneficiary(companyA));
        mvc.perform(authorized(post(confirmPath(offer)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(confirmation("100", "99"))).andExpect(status().isOk());
        UUID execution = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        jdbc.update("UPDATE transfer_executions SET status='REQUOTE_REQUIRED', payout_quote_receiver_amount=98, payout_quote_expires_at=now()+interval '5 minutes' WHERE id=?", execution);
        var provisioning = org.mockito.Mockito.mock(ages.vstable.backend.service.quote.BlindPayProvisioningService.class);
        when(provisioning.ensureManagedWallet(any(), any())).thenReturn(
                new ages.vstable.backend.service.quote.BlindPayProvisioningService.ManagedWallet("synthetic-wallet", "0x" + "1".repeat(40)));
        var payout = org.mockito.Mockito.mock(ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse.class);
        when(payout.id()).thenReturn("synthetic-payout");
        when(payout.status()).thenReturn("completed");
        when(blindpay.createEvmPayout(any(), any())).thenReturn(payout);
        var real = realProcessor(provisioning);
        org.mockito.Mockito.doAnswer(call -> {
            real.acceptRequote(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            return null;
        }).when(processor).acceptRequote(any(), any(), any(), any());
        MvcResult result = mvc.perform(authorized(post("/v1/transfer-executions/" + execution + "/requote/confirm"), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(confirmation("100", "98"))).andReturn();
        verify(blindpay, times(1)).createEvmPayout(any(), any());
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions WHERE id=?", String.class, execution)).isEqualTo("COMPLETED");
        assertThat(result.getResponse().getStatus()).as("Recotacao sem segundo fator chegou ao payout simulado concluido").isEqualTo(422);
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
        UserEntity user = users.saveAndFlush(UserEntity.builder().companyId(company)
                .fullName("Pessoa Sintetica").email(UUID.randomUUID() + "@example.test")
                .passwordHash(passwordHash).passwordSalt("unused-synthetic-salt")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).updatedAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        jdbc.update("INSERT INTO company_memberships(company_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                company, user.getId());
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
        return "{\"sourceAmount\":" + source + ",\"targetAmount\":" + target + "}";
    }
}
