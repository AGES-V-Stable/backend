package ages.vstable.backend.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.external.ai.SupportChatClient;
import ages.vstable.backend.external.ai.SupportChatGateway;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.SupportChatService;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Caracterização local de DoS / Disponibilidade L7 (sem carga destrutiva e sem chamadas externas).
 * PASS comprova ausência de HTTP 429, amplificação de payload e bypass de rate-limit em memória volátil.
 */
@Tag("pentest-impact")
@SpringBootTest(properties = {
        "transfers.reconciliation.enabled=false",
        "logging.level.root=ERROR",
        "avenia.base-url=http://127.0.0.1:0",
        "blindpay.base-url=http://127.0.0.1:0"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Testcontainers
class AvailabilityDosImpactAssessmentTest extends ImpactEvidence {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AdministratorRepository admins;
    @Autowired CompanyRepository companyRepository;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenUtils jwt;
    @Value("${api.auth.jwt.secret}") String signingSecret;
    @MockitoBean AveniaClient avenia;
    @MockitoBean BlindPayClient blindpay;
    @MockitoBean SupportChatClient supportChat;
    @MockitoBean BlindPayProvisioningService provisioning;

    private UUID companyA;
    private UserEntity owner;
    private String ownerToken;
    private String password;

    @BeforeEach
    void arrange() {
        assertThat(postgres.getJdbcUrl()).contains("jdbc:postgresql:");
        jdbc.execute("TRUNCATE companies, administrators, users, blindpay_webhook_events CASCADE");
        companyA = company();
        password = UUID.randomUUID() + "aA1!";
        owner = user(companyA, CompanyRole.OWNER);
        ownerToken = jwt.generateToken(owner);
    }

    @Test
    @DisplayName("P86-29 DOS-02 rajada controlada em login e onboarding sem HTTP 429 nem Retry-After")
    void loginAndOnboardingBurstsHaveNoRateLimit() throws Exception {
        int loginAttempts = 20;
        int onboardingAttempts = 15;
        List<Integer> loginStatuses = new ArrayList<>();
        List<Integer> onboardingStatuses = new ArrayList<>();
        boolean anyRetryAfter = false;

        long startedNs = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Map.Entry<Integer, String>>> loginFutures = new ArrayList<>();
            for (int i = 0; i < loginAttempts; i++) {
                loginFutures.add(pool.submit(() -> {
                    var res = mvc.perform(post("/v1/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(JSON.writeValueAsString(Map.of(
                                            "email", owner.getEmail(),
                                            "password", "WrongPassword123!"))))
                            .andReturn().getResponse();
                    return Map.entry(res.getStatus(), String.valueOf(res.getHeader("Retry-After")));
                }));
            }
            for (var future : loginFutures) {
                var entry = future.get(30, TimeUnit.SECONDS);
                loginStatuses.add(entry.getKey());
                if (!"null".equals(entry.getValue())) {
                    anyRetryAfter = true;
                }
            }

            List<Future<Map.Entry<Integer, String>>> onboardingFutures = new ArrayList<>();
            for (int i = 0; i < onboardingAttempts; i++) {
                final int idx = i;
                onboardingFutures.add(pool.submit(() -> {
                    String email = "dos-" + idx + "-" + UUID.randomUUID() + "@example.test";
                    var res = mvc.perform(post("/v1/onboarding/account")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(JSON.writeValueAsString(Map.of(
                                            "fullName", "Pessoa Sintetica " + idx,
                                            "email", email,
                                            "password", password,
                                            "confirmPassword", password,
                                            "enableTwoFactor", false))))
                            .andReturn().getResponse();
                    return Map.entry(res.getStatus(), String.valueOf(res.getHeader("Retry-After")));
                }));
            }
            for (var future : onboardingFutures) {
                var entry = future.get(30, TimeUnit.SECONDS);
                onboardingStatuses.add(entry.getKey());
                if (!"null".equals(entry.getValue())) {
                    anyRetryAfter = true;
                }
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);

        assertThat(loginStatuses).hasSize(loginAttempts).allMatch(s -> s == 401);
        assertThat(onboardingStatuses).hasSize(onboardingAttempts).allMatch(s -> s == 201);
        assertThat(anyRetryAfter).isFalse();
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("resultado", Map.of(
                "loginTentativas", loginAttempts,
                "loginHttp401", loginStatuses.size(),
                "onboardingTentativas", onboardingAttempts,
                "onboardingHttp201", onboardingStatuses.size(),
                "http429", 0,
                "retryAfterPresente", false,
                "elapsedMs", elapsedMs
        ));
    }

    @Test
    @DisplayName("P86-30 DOS-03 rajada controlada em POST /v1/quotes sem HTTP 429 persiste todas as cotacoes")
    void quoteBurstQueuesAndPersistsWithoutRateLimit() throws Exception {
        UUID beneficiaryId = bankBeneficiary(companyA);
        when(provisioning.ensureBankAccount(any(), any())).thenReturn("impact-bank");
        when(blindpay.createQuote(any(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return new BlindPayQuoteResponse(
                    "impact-quote-" + UUID.randomUUID(),
                    Instant.now().plusSeconds(300).toEpochMilli(),
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    new BigDecimal("9900"),
                    new BigDecimal("10000"),
                    BigDecimal.ZERO,
                    new BigDecimal("100"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null
            );
        });

        int requests = 15;
        List<Integer> statuses = new ArrayList<>();
        boolean anyRetryAfter = false;
        String payload = JSON.writeValueAsString(Map.ofEntries(
                Map.entry("beneficiaryId", beneficiaryId.toString()),
                Map.entry("direction", "PAYOUT"),
                Map.entry("sourceCurrency", "USDB"),
                Map.entry("targetCurrency", "USD"),
                Map.entry("sourcePaymentMethod", "wallet"),
                Map.entry("targetPaymentMethod", "wire"),
                Map.entry("amount", 100),
                Map.entry("amountSide", "SOURCE"),
                Map.entry("token", "USDB"),
                Map.entry("blockchainNetwork", "base_sepolia"),
                Map.entry("coverFees", false),
                Map.entry("description", "Rajada sintetica")
        ));

        long startedNs = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Future<Map.Entry<Integer, String>>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    var res = mvc.perform(auth(post("/v1/quotes"), ownerToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn().getResponse();
                    return Map.entry(res.getStatus(), String.valueOf(res.getHeader("Retry-After")));
                }));
            }
            for (var future : futures) {
                var entry = future.get(30, TimeUnit.SECONDS);
                statuses.add(entry.getKey());
                if (!"null".equals(entry.getValue())) {
                    anyRetryAfter = true;
                }
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);

        assertThat(statuses).hasSize(requests).allMatch(s -> s == 200);
        assertThat(anyRetryAfter).isFalse();
        assertThat(count("quote_requests")).isEqualTo(requests);
        verify(blindpay, org.mockito.Mockito.times(requests)).createQuote(any(), anyString());
        verifyNoInteractions(avenia, supportChat);

        observe("resultado", Map.of(
                "requisicoesConcorrentes", requests,
                "threads", 5,
                "http200", statuses.size(),
                "http429", 0,
                "retryAfterPresente", false,
                "quoteRequestsPersistidos", count("quote_requests"),
                "elapsedMs", elapsedMs
        ));
    }

    @Test
    @DisplayName("P86-31 DOS-04 amplificacao de payload e carregamento integral em memoria apesar de size=1")
    void unpaginatedListingsAndInMemoryMembersIgnoreDatabasePagination() throws Exception {
        for (int i = 0; i < 99; i++) {
            company();
        }
        for (int i = 0; i < 99; i++) {
            user(companyA, CompanyRole.OPERATOR);
        }
        for (int i = 0; i < 25; i++) {
            jdbc.update("""
                    INSERT INTO company_invitations(id,company_id,name,email,role,status,token_hash,token_ciphertext,token_key_version,created_by)
                    VALUES (?,?,?,?,'OPERATOR','PENDING',?,?, 'v1', ?)
                    """,
                    UUID.randomUUID(),
                    companyA,
                    "Convidado " + i,
                    "invite-" + i + "-" + UUID.randomUUID() + "@example.test",
                    UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                    "synthetic-encrypted",
                    owner.getId());
        }

        String admin = adminToken();
        for (String route : List.of("/v1/companies", "/v1/companies/summaries", "/v1/representatives")) {
            var res = mvc.perform(auth(get(route).param("page", "0").param("size", "1"), admin))
                    .andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
            String body = res.getContentAsString();
            assertThat(body).doesNotContain("passwordHash", "passwordSalt", "twoFactorSecret", signingSecret);
            JsonNode array = JSON.readTree(body);
            assertThat(array.isArray()).isTrue();
            assertThat(array.size()).isGreaterThanOrEqualTo(100);
            observe("GET " + route + "?page=0&size=1", Map.of(
                    "httpStatus", 200,
                    "registrosRetornados", array.size(),
                    "bytesUtf8", res.getContentAsByteArray().length
            ));
        }

        var membersRes = mvc.perform(auth(get("/v1/companies/" + companyA + "/members")
                        .param("page", "0").param("size", "1"), ownerToken))
                .andReturn().getResponse();
        assertThat(membersRes.getStatus()).isEqualTo(200);
        JsonNode membersJson = JSON.readTree(membersRes.getContentAsString());
        assertThat(membersJson.path("items").size()).isEqualTo(1);
        int totalMembers = membersJson.path("summary").path("activeMembers").asInt();
        int totalInvitations = membersJson.path("summary").path("pendingInvitations").asInt();
        long peopleCount = membersJson.path("summary").path("peopleCount").asLong();
        assertThat(totalMembers + totalInvitations).isGreaterThanOrEqualTo(50);
        assertThat(peopleCount).isEqualTo((long) totalMembers + totalInvitations);

        observe("GET /v1/companies/EMPRESA_A/members?page=0&size=1", Map.of(
                "httpStatus", 200,
                "itemsRetornados", membersJson.path("items").size(),
                "activeMembers", totalMembers,
                "pendingInvitations", totalInvitations,
                "peopleCount", peopleCount,
                "bytesUtf8", membersRes.getContentAsByteArray().length
        ));
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);
    }

    @Test
    @DisplayName("P86-32 DOS-05 SupportChatService retorna 423 sem Retry-After e permite bypass entre instancias")
    void supportChatRateLimitUsesVolatileMapAndReturns423WithoutRetryAfter() throws Exception {
        when(supportChat.generateReply(anyString(), any(), anyString(), anyString()))
                .thenReturn(new SupportChatGateway.SupportChatGatewayResult("Resposta sintetica", "mock"));

        String body = JSON.writeValueAsString(Map.of("message", "Como funciona o compliance?"));
        List<Integer> firstInstanceStatuses = new ArrayList<>();
        String sixteenthCode = "";
        boolean anyRetryAfter = false;

        for (int i = 0; i < 16; i++) {
            var res = mvc.perform(auth(post("/v1/support/chat"), ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse();
            firstInstanceStatuses.add(res.getStatus());
            if (res.getHeader("Retry-After") != null) {
                anyRetryAfter = true;
            }
            if (i == 15) {
                sixteenthCode = JSON.readTree(res.getContentAsString()).path("code").asText("");
            }
        }

        assertThat(firstInstanceStatuses.subList(0, 15)).allMatch(s -> s == 200);
        assertThat(firstInstanceStatuses.get(15)).isEqualTo(423);
        assertThat(firstInstanceStatuses).doesNotContain(429);
        assertThat(sixteenthCode).isEqualTo("ACCOUNT_LOCKED");
        assertThat(anyRetryAfter).isFalse();

        // Segunda instância isolada (simulando outra Lambda) reinicia o ConcurrentHashMap em memória.
        SupportChatService secondInstance = new SupportChatService(supportChat, companyRepository);
        var authPrincipal = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());
        var req = new ages.vstable.backend.dto.support.SupportChatRequestDTO("Pergunta na segunda instancia", List.of());

        int secondInstanceAccepted = 0;
        for (int i = 0; i < 15; i++) {
            var reply = secondInstance.chat(authPrincipal, req);
            assertThat(reply.getReply()).isEqualTo("Resposta sintetica");
            secondInstanceAccepted++;
        }
        assertThatThrownBy(() -> secondInstance.chat(authPrincipal, req))
                .isInstanceOf(LockedException.class);
        assertThat(secondInstanceAccepted).isEqualTo(15);

        verifyNoInteractions(avenia, blindpay, provisioning);
        observe("resultado", Map.of(
                "instanciaA_200", 15,
                "instanciaA_16aStatus", 423,
                "instanciaA_16aCode", sixteenthCode,
                "http429", 0,
                "retryAfterPresente", false,
                "instanciaB_aceitasMesmoUsuario", secondInstanceAccepted
        ));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        String digits = Long.toString(ThreadLocalRandom.current().nextLong(100000000000L, 1000000000000L));
        for (int[] weights : List.of(new int[]{5,4,3,2,9,8,7,6,5,4,3,2}, new int[]{6,5,4,3,2,9,8,7,6,5,4,3,2})) {
            int sum = 0;
            for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
            digits += sum % 11 < 2 ? 0 : 11 - sum % 11;
        }
        jdbc.update("INSERT INTO companies(id,legal_name,cnpj) VALUES (?,'Empresa Sintetica',?)", id, digits);
        return id;
    }

    private UserEntity user(UUID company, CompanyRole role) {
        var user = users.saveAndFlush(UserEntity.builder()
                .companyId(company)
                .fullName("Pessoa Sintetica")
                .email(UUID.randomUUID() + "@example.test")
                .passwordHash(passwords.encode(password))
                .passwordSalt("synthetic")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
        jdbc.update("INSERT INTO company_memberships(company_id,user_id,role,status) VALUES (?,?,?,'ACTIVE')",
                company, user.getId(), role.name());
        return user;
    }

    private String adminToken() {
        var admin = admins.saveAndFlush(AdministratorEntity.builder()
                .fullName("Admin Sintetico")
                .email(UUID.randomUUID() + "@example.test")
                .passwordHash("unused")
                .passwordSalt("synthetic")
                .accessLevel(AdminAccessLevel.SUPER_ADMIN)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
        return jwt.generateToken(admin);
    }

    private UUID bankBeneficiary(UUID company) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO beneficiaries(id,company_id,nickname,receiving_method,currency,payment_rail)
                VALUES (?,?,'Beneficiario Sintetico','BANK_ACCOUNT'::receiving_method_enum,'USD','wire')
                """, id, company);
        return id;
    }
}
