package ages.vstable.backend.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.external.ai.SupportChatClient;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TotpService;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Caracterização de Não-Repúdio (STRIDE R) e trilha de auditoria.
 * PASS comprova lacunas de persistência de autoria, cascata sem trilha, forja de X-Request-ID e ausência de integridade no PDF.
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
class NonRepudiationImpactAssessmentTest extends ImpactEvidence {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("blindpay.instance-id", () -> "impact-synthetic-instance");
        registry.add("blindpay.webhook-secret", () -> "whsec_dGVzdC13ZWJob29rLXNlY3JldC0zMi1ieXRlcw==");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AdministratorRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenUtils jwt;
    @Autowired TotpService totp;
    @Value("${api.auth.jwt.secret}") String signingSecret;
    @MockitoBean AveniaClient avenia;
    @MockitoBean BlindPayClient blindpay;
    @MockitoBean SupportChatClient supportChat;
    @MockitoBean BlindPayProvisioningService provisioning;

    private UUID companyA;
    private UUID companyB;
    private UserEntity ownerA;
    private String ownerAToken;
    private String password;

    @BeforeEach
    void arrange() {
        assertThat(postgres.getJdbcUrl()).contains("jdbc:postgresql:");
        jdbc.execute("TRUNCATE companies, administrators, users, blindpay_webhook_events CASCADE");
        companyA = company();
        companyB = company();
        password = UUID.randomUUID() + "aA1!";
        ownerA = user(companyA, CompanyRole.OWNER);
        ownerAToken = jwt.generateToken(ownerA);
    }

    @Test
    @DisplayName("P86-NR-01 NR-01 compliance e exclusao em cascata sem trilha ou administrator_id")
    void adminComplianceAndCascadeDeleteLeaveNoAuditTrail() throws Exception {
        UserEntity ownerB = user(companyB, CompanyRole.OWNER);
        String ownerBToken = jwt.generateToken(ownerB);
        http(auth(post("/v1/companies/" + companyB + "/invitations"), ownerBToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Convidado B\",\"email\":\"invited-b@example.test\",\"role\":\"OPERATOR\"}"),
                201, "POST /v1/companies/EMPRESA_B/invitations");
        jdbc.update("""
                INSERT INTO compliance_documents(company_id,document_type,file_name,file_url)
                VALUES (?,'ARTICLES_OF_INCORPORATION'::document_type_enum,'contrato.pdf','https://files.example.test/contrato.pdf')
                """, companyB);

        String adminToken = adminToken();
        http(auth(patch("/v1/companies/" + companyB + "/compliance-status"), adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusKyb\":\"APPROVED\",\"statusAml\":\"APPROVED\"}"),
                200, "PATCH /v1/companies/EMPRESA_B/compliance-status");

        String cnpjB = jdbc.queryForObject("SELECT cnpj FROM companies WHERE id=?", String.class, companyB);
        http(auth(put("/v1/companies/" + companyB), adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "legalName", "Empresa B Editada Sem Auditoria",
                        "cnpj", cnpjB,
                        "country", "Brasil",
                        "state", "RS",
                        "city", "Porto Alegre",
                        "zipCode", "90000000"))),
                200, "PUT /v1/companies/EMPRESA_B");

        http(auth(delete("/v1/companies/" + companyB), adminToken), 204, "DELETE /v1/companies/EMPRESA_B");

        Integer auditTables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE '%audit%'
                """, Integer.class);
        Integer adminAuthorColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'companies'
                  AND column_name IN ('administrator_id','updated_by','deleted_by')
                """, Integer.class);

        assertThat(auditTables).isZero();
        assertThat(adminAuthorColumns).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE id=?", Integer.class, companyB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE company_id=?", Integer.class, companyB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM compliance_documents WHERE company_id=?", Integer.class, companyB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_invitations WHERE company_id=?", Integer.class, companyB)).isZero();
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("resultado", Map.of(
                "auditTables", auditTables,
                "companiesAdminColumns", adminAuthorColumns,
                "usersRestantesB", 0,
                "complianceDocsRestantesB", 0,
                "convitesRestantesB", 0
        ));
    }

    @Test
    @DisplayName("P86-NR-02 NR-02 recotacao por OPERATOR B nao registra autoria e mantem requested_by de OPERATOR A")
    void requoteConfirmPreservesInitialRequesterAndDiscardsConfirmingOperator() throws Exception {
        UserEntity operatorA = user(companyA, CompanyRole.OPERATOR);
        UserEntity operatorB = user(companyA, CompanyRole.OPERATOR);
        mockPayout();
        UUID beneficiary = beneficiary(companyA);
        UUID offer = offer(companyA, operatorA, beneficiary);

        http(auth(post("/v1/quotes/offers/" + offer + "/confirm"), jwt.generateToken(operatorA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAmount\":100,\"targetAmount\":99}"),
                200, "POST /v1/quotes/offers/OFERTA/confirm OPERATOR_A");

        UUID executionId = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        jdbc.update("""
                UPDATE transfer_executions
                SET status='REQUOTE_REQUIRED', external_payout_id=NULL,
                    payout_quote_receiver_amount=98, payout_quote_expires_at=now() + interval '5 minutes'
                WHERE id=?
                """, executionId);

        http(auth(post("/v1/transfer-executions/" + executionId + "/requote/confirm"), jwt.generateToken(operatorB))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAmount\":100,\"targetAmount\":98}"),
                200, "POST /v1/transfer-executions/EXEC/requote/confirm OPERATOR_B");

        UUID recordedExecutionRequester = jdbc.queryForObject(
                "SELECT requested_by FROM transfer_executions WHERE id=?", UUID.class, executionId);
        UUID recordedTxCreator = jdbc.queryForObject(
                "SELECT creator_user_id FROM base_transactions", UUID.class);
        String executionJsonRow = jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM transfer_executions t WHERE id=?", String.class, executionId);
        String txJsonRow = jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM base_transactions t LIMIT 1", String.class);

        assertThat(recordedExecutionRequester).isEqualTo(operatorA.getId());
        assertThat(recordedTxCreator).isEqualTo(operatorA.getId());
        assertThat(executionJsonRow).doesNotContain(operatorB.getId().toString());
        assertThat(txJsonRow).doesNotContain(operatorB.getId().toString());
        verify(blindpay, org.mockito.Mockito.times(2)).createEvmPayout(any(), anyString());

        observe("resultado", Map.of(
                "requestedByMantido", "OPERATOR_A",
                "operatorBRegistradoEmExecucao", false,
                "operatorBRegistradoEmTransacao", false,
                "statusFinal", "COMPLETED"
        ));
    }

    @Test
    @DisplayName("P86-NR-03 NR-03 login, falha 2FA, revogacao de convite e webhook rejeitado sem trilha persistente")
    void authInvitationAndWebhookEventsLackPersistentAuditTrail() throws Exception {
        ownerA.setTwoFactorEnabled(true);
        ownerA.setTwoFactorSecret(totp.generateSecret());
        users.saveAndFlush(ownerA);

        JsonNode loginChallenge = http(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", ownerA.getEmail(), "password", password))),
                200, "POST /v1/auth/login challenge 2FA");
        String challengeToken = loginChallenge.path("challengeToken").asText();
        assertThat(challengeToken).isNotBlank();

        http(post("/v1/auth/login/verify-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("challengeToken", challengeToken, "code", "000000"))),
                401, "POST /v1/auth/login/verify-2fa codigo invalido");

        var createInviteRes = mvc.perform(auth(post("/v1/companies/" + companyA + "/invitations"), ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Convidado NR\",\"email\":\"nr-invite@example.test\",\"role\":\"OPERATOR\"}"))
                .andReturn().getResponse();
        assertThat(createInviteRes.getStatus()).isEqualTo(201);
        UUID invitationId = UUID.fromString(JSON.readTree(createInviteRes.getContentAsString()).path("invitation").path("id").asText());
        String etag = createInviteRes.getHeader("ETag");

        http(auth(delete("/v1/companies/" + companyA + "/invitations/" + invitationId), ownerAToken)
                .header("If-Match", etag),
                204, "DELETE /v1/companies/EMPRESA_A/invitations/CONV_A");

        String rejectedSvixId = "msg-invalid-" + UUID.randomUUID();
        http(post("/v1/webhooks/blindpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", rejectedSvixId)
                .header("svix-timestamp", String.valueOf(Instant.now().getEpochSecond()))
                .header("svix-signature", "v1,invalida==")
                .content("{\"webhook_event\":\"customer.update\"}"),
                401, "POST /v1/webhooks/blindpay assinatura invalida");

        String inviteStatus = jdbc.queryForObject(
                "SELECT status FROM company_invitations WHERE id=?", String.class, invitationId);
        OffsetDateTime revokedAt = jdbc.queryForObject(
                "SELECT revoked_at FROM company_invitations WHERE id=?", OffsetDateTime.class, invitationId);
        Integer inviteActorCols = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_invitations'
                  AND column_name IN ('revoked_by','regenerated_by')
                """, Integer.class);
        Integer rejectedWebhookCount = jdbc.queryForObject(
                "SELECT count(*) FROM blindpay_webhook_events WHERE message_id=?", Integer.class, rejectedSvixId);
        Integer authAuditTables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND (table_name LIKE '%audit%' OR table_name LIKE '%login%' OR table_name LIKE '%auth_event%')
                """, Integer.class);

        assertThat(inviteStatus).isEqualTo("REVOKED");
        assertThat(revokedAt).isNotNull();
        assertThat(inviteActorCols).isZero();
        assertThat(rejectedWebhookCount).isZero();
        assertThat(authAuditTables).isZero();
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("resultado", Map.of(
                "conviteStatus", inviteStatus,
                "colunasRevokedByOuRegeneratedBy", inviteActorCols,
                "webhookRejeitadoPersistido", rejectedWebhookCount,
                "tabelasAuditoriaAuth", authAuditTables
        ));
    }

    @Test
    @DisplayName("P86-NR-04 NR-04 aceitacao acritica de X-Request-ID permite colisao mas bloqueia CRLF")
    void requestIdAcceptsClientSuppliedCollisionWhileSanitizingCrlf() throws Exception {
        String forgedUuid = "11111111-1111-1111-1111-111111111111";
        var res1 = mvc.perform(auth(get("/v1/users/me"), ownerAToken)
                        .header("X-Request-ID", forgedUuid))
                .andReturn().getResponse();
        var res2 = mvc.perform(auth(get("/v1/users/me"), ownerAToken)
                        .header("X-Request-ID", forgedUuid))
                .andReturn().getResponse();
        var resCrlf = mvc.perform(auth(get("/v1/users/me"), ownerAToken)
                        .header("X-Request-ID", "bad\r\nInjected: true"))
                .andReturn().getResponse();

        assertThat(res1.getStatus()).isEqualTo(200);
        assertThat(res2.getStatus()).isEqualTo(200);
        assertThat(resCrlf.getStatus()).isEqualTo(200);

        String echo1 = res1.getHeader("X-Request-ID");
        String echo2 = res2.getHeader("X-Request-ID");
        String echoCrlf = resCrlf.getHeader("X-Request-ID");

        assertThat(echo1).isEqualTo(forgedUuid);
        assertThat(echo2).isEqualTo(forgedUuid);
        assertThat(echoCrlf).isNotNull().isNotEqualTo(forgedUuid).doesNotContain("\r", "\n", "Injected");
        assertThat(UUID.fromString(echoCrlf)).isNotNull();
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("resultado", Map.of(
                "forgedUuidEco1", echo1,
                "forgedUuidEco2", echo2,
                "colisaoAceita", echo1.equals(echo2),
                "crlfSubstituidoPorUuidValido", true
        ));
    }

    @Test
    @DisplayName("P86-NR-05 NR-05 comprovante PDF sem assinatura digital, hash de integridade ou autor")
    void receiptPdfHasNoSignatureIntegrityHashOrCreatorIdentity() throws Exception {
        UserEntity operatorA = user(companyA, CompanyRole.OPERATOR);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id,status) VALUES (?,'APPROVED')", ownerA.getId());
        UUID beneficiary = beneficiary(companyA);
        UUID txId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO base_transactions(id,company_id,creator_user_id,status,foreign_currency,foreign_amount,settlement_amount_brl,service_fee_brl,exchange_rate,settled_at)
                VALUES (?,?,?,'SETTLED'::transaction_status_enum,'USD',100.00,520.00,10.00,5.100000,now())
                """, txId, companyA, operatorA.getId());
        jdbc.update("""
                INSERT INTO import_transactions(transaction_id,beneficiary_id,transfer_method)
                VALUES (?,?,'PIX'::transfer_method_enum)
                """, txId, beneficiary);

        var res = mvc.perform(auth(get("/api/transferencias/" + txId + "/comprovante"), ownerAToken))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).contains(MediaType.APPLICATION_PDF_VALUE);

        byte[] pdfBytes = res.getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();

        String extractedText;
        boolean signaturesEmpty;
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            signaturesEmpty = doc.getSignatureDictionaries().isEmpty();
            extractedText = new PDFTextStripper().getText(doc);
        }

        assertThat(signaturesEmpty).isTrue();
        assertThat(extractedText).contains(txId.toString());
        assertThat(extractedText).doesNotContain(
                operatorA.getId().toString(),
                operatorA.getEmail(),
                operatorA.getFullName(),
                "SHA-256",
                "HMAC",
                "Assinatura"
        );
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("resultado", Map.of(
                "httpStatus", 200,
                "pdfBytes", pdfBytes.length,
                "assinaturasPdfBox", 0,
                "autorNoPdf", false,
                "hashIntegridadeNoPdf", false
        ));
    }

    private JsonNode http(MockHttpServletRequestBuilder request, int expected, String step) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        observe(step, Map.of("httpStatus", response.getStatus()));
        assertThat(response.getStatus()).as(step).isEqualTo(expected);
        String body = response.getContentAsString();
        assertThat(body).doesNotContain("passwordHash", "passwordSalt", "twoFactorSecret", "stackTrace", signingSecret);
        return body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private void mockPayout() {
        when(provisioning.ensureManagedWallet(any(), any()))
                .thenReturn(new BlindPayProvisioningService.ManagedWallet("impact-wallet", "0x" + "1".repeat(40)));
        var payout = mock(BlindPayPayoutResponse.class);
        when(payout.id()).thenReturn("impact-payout");
        when(payout.status()).thenReturn("completed");
        when(blindpay.createEvmPayout(any(), anyString())).thenReturn(payout);
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
                .fullName("Operador " + UUID.randomUUID().toString().substring(0, 8))
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

    private UUID beneficiary(UUID company) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO beneficiaries(id,company_id,nickname) VALUES (?,?,'Beneficiario Sintetico')", id, company);
        return id;
    }

    private UUID offer(UUID company, UserEntity user, UUID beneficiary) {
        UUID quote = UUID.randomUUID();
        UUID offer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quote_requests(id,company_id,requested_by,beneficiary_id,direction,source_currency,target_currency,
                source_payment_method,target_payment_method,requested_amount,amount_side,token,blockchain_network,status)
                VALUES (?,?,?,?,'PAYOUT','USDB','USD','wallet','wire',100,'SOURCE','USDB','base_sepolia','COMPLETED')
                """, quote, company, user.getId(), beneficiary);
        jdbc.update("""
                INSERT INTO provider_quotes(id,quote_request_id,provider,external_quote_id,idempotency_key,status,
                source_amount,target_amount,exchange_rate,total_fee,expires_at)
                VALUES (?,?,'BLINDPAY','impact-quote',?,'SELECTED',100,99,0.99,1,now()+interval '5 minutes')
                """, offer, quote, "impact:" + offer);
        return offer;
    }
}
