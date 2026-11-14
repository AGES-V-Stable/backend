package ages.vstable.backend.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TotpService;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.service.transfer.BlindPayExecutionProcessor;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Caracterização de explorabilidade: PASS significa que a observação documentada foi demonstrada,
 * inclusive quando se trata de vulnerabilidade. Não são asserts de remediação da #85.
 * HTTP passa pelo filtro JWT real; apenas as fronteiras externas/provisionamento são simuladas.
 */
@Tag("pentest-impact")
@SpringBootTest(properties = {"transfers.reconciliation.enabled=false", "logging.level.root=ERROR",
        "avenia.base-url=http://127.0.0.1:0", "blindpay.base-url=http://127.0.0.1:0"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Testcontainers
class ApplicationImpactAssessmentTest extends ImpactEvidence {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    private static final byte[] WEBHOOK_KEY = Jwts.SIG.HS256.key().build().getEncoded();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("blindpay.instance-id", () -> "impact-synthetic-instance");
        registry.add("blindpay.webhook-secret", () -> "whsec_" + Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AdministratorRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenUtils jwt;
    @Autowired TotpService totp;
    @Autowired BlindPayExecutionProcessor processor;
    @Value("${api.auth.jwt.secret}") String signingSecret;
    @MockitoBean AveniaClient avenia;
    @MockitoBean BlindPayClient blindpay;
    @MockitoBean ages.vstable.backend.external.ai.SupportChatClient supportChat;
    @MockitoBean BlindPayProvisioningService provisioning;
    UUID companyA;
    UUID companyB;
    UserEntity owner;
    String token;
    String password;

    @BeforeEach
    void arrange() {
        // Nenhum banco pré-existente: credenciais e URL são exclusivamente as deste container.
        assertThat(postgres.getJdbcUrl()).contains("jdbc:postgresql:");
        jdbc.execute("TRUNCATE companies, administrators, users, blindpay_webhook_events CASCADE");
        companyA = company();
        companyB = company();
        password = UUID.randomUUID() + "aA1!";
        owner = user(companyA, CompanyRole.OWNER);
        owner.setTwoFactorEnabled(true);
        owner.setTwoFactorSecret(totp.generateSecret());
        users.saveAndFlush(owner);
        token = jwt.generateToken(owner); // sessão previamente obtida; não simula roubo do token.
    }

    @Test
    @DisplayName("P86-01 CHAIN-01 JWT antigo confirma oferta sem TOTP ate payout simulado")
    void retainedSessionCanSubmitSimulatedPayoutWithoutTotp() throws Exception {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(UUID.randomUUID().toString()), owner.getId());
        http(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("email", owner.getEmail(), "password", password))), 401, "POST /v1/auth/login senha antiga [REDACTED]");
        http(auth(get("/v1/users/me"), token), 200, "GET /v1/users/me JWT anterior [REDACTED]");
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(18_000_000L);
        observe("TTL configurado em segundos; nao houve espera de 5h", 18000);
        mockPayout();
        UUID offer = offer(companyA, owner, beneficiary(companyA));
        confirm(offer, token, "100", "99", 200);
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM base_transactions", Integer.class)).isEqualTo(1);
        verify(blindpay).createEvmPayout(any(), anyString());
        verifyNoInteractions(avenia);
        observe("resultado", Map.of("twoFactorEnabled", true, "totpEnviado", false,
                "payoutSimulado", 1, "execucoes", 1, "historicos", 1, "estado", "COMPLETED"));
    }

    @Test
    @DisplayName("P86-02 CHAIN-01 recotacao sem TOTP chega ao payout simulado")
    void requoteCanSubmitWithoutTotp() throws Exception {
        mockPayout();
        UUID offer = offer(companyA, owner, beneficiary(companyA));
        confirm(offer, token, "100", "99", 200);
        UUID execution = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        // Pré-condição específica do ramo: estado de recotação sem nova chamada externa.
        jdbc.update("UPDATE transfer_executions SET status='REQUOTE_REQUIRED', external_payout_id=NULL, "
                + "payout_quote_receiver_amount=98, payout_quote_expires_at=now()+interval '5 minutes' WHERE id=?", execution);
        clearInvocations(blindpay);
        http(auth(post("/v1/transfer-executions/" + execution + "/requote/confirm"), token)
                .contentType(MediaType.APPLICATION_JSON).content(amounts("100", "98")), 200,
                "POST /v1/transfer-executions/EXEC_A/requote/confirm sem TOTP");
        verify(blindpay).createEvmPayout(any(), anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_executions", String.class)).isEqualTo("COMPLETED");
        observe("precondicao", "REQUOTE_REQUIRED preparado no banco efemero; nao encadeado como transicao natural de COMPLETED");
        observe("payoutSimuladoAposRecotacao", 1);
    }

    @Test
    @DisplayName("P86-03 CHAIN-01 rota direta bloqueia ausencia do fator")
    void directTransferRequiresSecondFactor() throws Exception {
        UUID beneficiary = beneficiary(companyA);
        http(auth(post("/v1/transfers"), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"amount":100,"amountType":"SOURCE","destinationCurrency":"USD","paymentMethod":"PIX","beneficiaryId":"%s"}
                """.formatted(beneficiary)), 422, "POST /v1/transfers sem TOTP");
        verifyNoInteractions(avenia, blindpay);
        assertThat(count("transfer_executions")).isZero();
        observe("execucoes", 0);
    }

    @Test
    @DisplayName("P86-04 CHAIN-02 Admin altera compliance humano e cadastrais")
    void adminCanManuallyDecideCompliance() throws Exception {
        String admin = adminToken();
        assertThat(jdbc.queryForObject("SELECT kyb_status::text FROM companies WHERE id=?", String.class, companyB)).isEqualTo("PENDING");
        for (String decision : List.of("APPROVED", "REJECTED")) {
            http(auth(patch("/v1/companies/" + companyB + "/compliance-status"), admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"statusKyb\":\"" + decision + "\",\"statusAml\":\"" + decision + "\"}"),
                    200, "PATCH /v1/companies/EMPRESA_B/compliance-status " + decision);
            assertThat(jdbc.queryForObject("SELECT kyb_status::text FROM companies WHERE id=?", String.class, companyB)).isEqualTo(decision);
            assertThat(jdbc.queryForObject("SELECT aml_status::text FROM companies WHERE id=?", String.class, companyB)).isEqualTo(decision);
        }
        verifyNoInteractions(avenia, blindpay);
        observe("decisoesPersistidasSemProvedor", List.of("APPROVED", "REJECTED"));
        observe("regra", "Admin unico; nao se alega escalada SUPPORT para SUPER_ADMIN");
        String cnpj = jdbc.queryForObject("SELECT cnpj FROM companies WHERE id=?", String.class, companyB);
        http(auth(put("/v1/companies/" + companyB), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("legalName", "Empresa Sintetica Editada", "cnpj", cnpj,
                        "country", "Brasil", "state", "RS", "city", "Cidade de Teste", "zipCode", "00000000"))),
                200, "PUT /v1/companies/EMPRESA_B Admin dados sinteticos [REDACTED]");
        assertThat(jdbc.queryForObject("SELECT legal_name FROM companies WHERE id=?", String.class, companyB)).isEqualTo("Empresa Sintetica Editada");
        observe("edicaoCadastral", "Alteracao confirmada no banco; capacidade autorizada do Admin");
    }

    @Test
    @DisplayName("P86-05 CHAIN-02 alcance de leitura Admin em duas empresas sinteticas")
    void measureAdminReadScopeWithoutCallingItPrivilegeEscalation() throws Exception {
        UserEntity other = user(companyB, CompanyRole.OWNER);
        UUID beneficiaryB = beneficiary(companyB);
        mockPayout();
        UUID offerB = offer(companyB, other, beneficiaryB);
        confirm(offerB, jwt.generateToken(other), "100", "99", 200);
        String admin = adminToken();
        JsonNode companies = http(auth(get("/v1/companies"), admin), 200, "GET /v1/companies Admin");
        assertThat(companies.toString()).contains(companyA.toString(), companyB.toString());
        JsonNode company = http(auth(get("/v1/companies/" + companyB), admin), 200, "GET /v1/companies/EMPRESA_B Admin");
        assertThat(company.toString()).contains(companyB.toString());
        JsonNode beneficiaries = http(auth(get("/v1/companies/" + companyB + "/beneficiaries"), admin), 200,
                "GET /v1/companies/EMPRESA_B/beneficiaries Admin");
        assertThat(beneficiaries.toString()).contains(beneficiaryB.toString());
        JsonNode representatives = http(auth(get("/v1/representatives"), admin), 200, "GET /v1/representatives Admin");
        assertThat(representatives.size()).isEqualTo(2);
        assertThat(representatives.toString()).contains(owner.getId().toString(), other.getId().toString());
        http(auth(get("/api/transferencias"), admin), 403, "GET /api/transferencias Admin sem vinculo");
        http(auth(get("/v1/dashboard/transaction-volume"), admin), 403, "GET /v1/dashboard/transaction-volume Admin sem vinculo");
        observe("massaTotal", Map.of("companies", count("companies"), "users", count("users"),
                "beneficiaries", count("beneficiaries"), "base_transactions", count("base_transactions")));
        observe("alcanceComprovado", "Duas empresas e dois representantes visiveis; detalhe B e beneficiario B visiveis; historico e dashboard bloqueados sem vinculo empresarial");
    }

    @Test
    @DisplayName("P86-06 CHAIN-02 exclusao autorizada restrita a empresa sem dependencias")
    void deletionScopeIsMeasuredOnEmptySyntheticCompany() throws Exception {
        http(auth(delete("/v1/companies/" + companyB), adminToken()), 204, "DELETE /v1/companies/EMPRESA_B vazia Admin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE id=?", Integer.class, companyB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE id=?", Integer.class, companyA)).isEqualTo(1);
        observe("alcance", "1 empresa vazia removida; empresa A preservada; operacao permitida ao Admin, nao vulnerabilidade");
        verifyNoInteractions(avenia, blindpay);
    }

    @Test
    @DisplayName("P86-07 CHAIN-02 USER nao escala para Admin nem atravessa empresas")
    void horizontalAndVerticalAttemptsAreBlocked() throws Exception {
        UserEntity other = user(companyB, CompanyRole.OWNER);
        UUID beneficiaryB = beneficiary(companyB);
        mockPayout();
        UUID offerB = offer(companyB, other, beneficiaryB);
        confirm(offerB, jwt.generateToken(other), "100", "99", 200);
        UUID execution = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        UUID transaction = jdbc.queryForObject("SELECT transaction_id FROM transfer_executions", UUID.class);
        clearInvocations(blindpay);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id,status) VALUES (?,'APPROVED')", owner.getId());
        http(auth(get("/v1/companies/" + companyB), token), 403, "GET /v1/companies/EMPRESA_B USER A");
        http(auth(get("/v1/companies/" + companyA + "/beneficiaries/" + beneficiaryB), token), 404,
                "GET /v1/companies/EMPRESA_A/beneficiaries/BENEF_B USER A");
        http(auth(get("/v1/transfer-executions/" + execution), token), 404, "GET /v1/transfer-executions/EXEC_B USER A");
        http(auth(get("/api/transferencias/" + transaction), token), 404, "GET /api/transferencias/TX_B USER A");
        http(auth(patch("/v1/companies/" + companyB + "/compliance-status"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"statusKyb\":\"APPROVED\"}"), 403,
                "PATCH /v1/companies/EMPRESA_B/compliance-status USER A");
        http(auth(delete("/v1/companies/" + companyB), token), 403, "DELETE /v1/companies/EMPRESA_B USER A");
        confirm(offerB, token, "100", "99", 404);
        verifyNoInteractions(avenia, blindpay);
        observe("falhasBolaOuEscalada", 0);
    }

    @Test
    @DisplayName("P86-08 CHAIN-01 desafio nao substitui sessao e JWT adulterado nao autentica")
    void challengeAndForgedTokensCannotBecomeSessions() throws Exception {
        http(auth(get("/v1/users/me"), jwt.generateChallengeToken(owner)), 401, "GET /v1/users/me challenge [REDACTED]");
        String[] parts = token.split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"admin@example.test\",\"account_type\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        http(auth(get("/v1/companies"), String.join(".", parts)), 401, "GET /v1/companies JWT adulterado [REDACTED]");
        verifyNoInteractions(avenia, blindpay);
    }

    @Test
    @DisplayName("P86-09 CHAIN-03 JWT antigo cria convite pendente mas revogacao corta acesso")
    void persistenceAttemptCreatesPendingInvitationNotAnActiveAccount() throws Exception {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(UUID.randomUUID().toString()), owner.getId());
        http(auth(post("/v1/companies/" + companyA + "/invitations"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pessoa Sintetica\",\"email\":\"invited@example.test\",\"role\":\"OPERATOR\"}"),
                201, "POST /v1/companies/EMPRESA_A/invitations OPERATOR email [REDACTED]");
        assertThat(count("company_invitations")).isEqualTo(1);
        assertThat(count("company_memberships")).isEqualTo(1);
        observe("persistencia", "1 convite PENDING persistido; nenhum membro novo nem login do convidado demonstrado");
        jdbc.update("UPDATE company_memberships SET status='REMOVED',removed_at=now() WHERE user_id=?", owner.getId());
        http(auth(get("/v1/balance"), token), 403, "GET /v1/balance JWT antigo apos revogar vinculo");
        http(auth(post("/v1/companies/" + companyA + "/invitations"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pessoa Sintetica\",\"email\":\"blocked@example.test\",\"role\":\"OPERATOR\"}"),
                403, "POST /v1/companies/EMPRESA_A/invitations apos revogar vinculo");
        assertThat(count("company_invitations")).isEqualTo(1);
        verifyNoInteractions(avenia, blindpay);
    }

    @Test
    @DisplayName("P86-10 CHAIN-03 OPERATOR e VIEWER nao criam acesso persistente")
    void lowerCompanyRolesCannotCreateInvitationOrElevateRole() throws Exception {
        for (CompanyRole role : List.of(CompanyRole.OPERATOR, CompanyRole.VIEWER)) {
            String member = jwt.generateToken(user(companyA, role));
            http(auth(post("/v1/companies/" + companyA + "/invitations"), member)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pessoa Sintetica\",\"email\":\"blocked@example.test\",\"role\":\"OPERATOR\"}"),
                    403, "POST /v1/companies/EMPRESA_A/invitations " + role);
        }
        http(auth(post("/v1/companies/" + companyA + "/invitations"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pessoa Sintetica\",\"email\":\"blocked@example.test\",\"role\":\"OWNER\"}"),
                400, "POST /v1/companies/EMPRESA_A/invitations tentativa OWNER");
        assertThat(count("company_invitations")).isZero();
        verifyNoInteractions(avenia, blindpay);
    }

    @Test
    @DisplayName("P86-11 CHAIN-04 sem TOTP envio Avenia simulado preserva 8 casas mas historico perde precisao")
    void simulatedCryptoTicketExposesAccountingDifference() throws Exception {
        UUID beneficiary = beneficiary(companyA);
        jdbc.update("UPDATE beneficiaries SET receiving_method='CRYPTO_WALLET',currency='USDC',blockchain_network='polygon'::blockchain_network_enum,avenia_wallet_id=?,avenia_sub_account_id='impact-sub' WHERE id=?", UUID.randomUUID(), beneficiary);
        jdbc.update("INSERT INTO avenia_kyc_verifications(user_id,status,avenia_sub_account_id) VALUES (?,'APPROVED','impact-sub')", owner.getId());
        UUID offer = offer(companyA, owner, beneficiary);
        jdbc.update("UPDATE quote_requests SET source_currency='BRL',target_currency='USDC' WHERE id=(SELECT quote_request_id FROM provider_quotes WHERE id=?)", offer);
        jdbc.update("UPDATE provider_quotes SET provider='AVENIA',target_amount=19.12345678 WHERE id=?", offer);
        when(avenia.createQuote(any())).thenReturn(new AveniaQuoteResponse("impact-quote", "BRL", "PIX", new BigDecimal("100"),
                "USDC", "POLYGON", new BigDecimal("19.12345678"), BigDecimal.ONE, "BRL", false, false, List.of(), new BigDecimal("5"), "impact-pair", "IMMEDIATE"));
        when(avenia.createTicket(any())).thenReturn(new AveniaTicketResponse(UUID.randomUUID(), null, OffsetDateTime.now().plusMinutes(5), "UNPAID", null, null));
        confirm(offer, token, "100", "19.12345678", 200);
        verify(avenia).createTicket(any());
        assertThat(jdbc.queryForObject("SELECT target_amount FROM transfer_executions", BigDecimal.class)).isEqualByComparingTo("19.12345678");
        assertThat(jdbc.queryForObject("SELECT foreign_amount FROM base_transactions", BigDecimal.class)).isEqualByComparingTo("19.12");
        observe("valoresSinteticos", Map.of("executionUSDC", "19.12345678", "historyUSDC", "19.12", "differenceUSDC", "0.00345678"));
        observe("alcance", "1 createTicket mock; nao houve liquidacao nem transferencia real");
        verifyNoInteractions(blindpay);
    }

    @Test
    @DisplayName("P86-12 CHAIN-04 adulteracao e expiracao bloqueiam execucao")
    void alteredAmountsAndExpiredOffersAreBlockedBeforeProvider() throws Exception {
        UUID offer = offer(companyA, owner, beneficiary(companyA));
        confirm(offer, token, "1", "99", 422);
        confirm(offer, token, "100", "999", 422);
        jdbc.update("UPDATE provider_quotes SET expires_at=now()-interval '1 minute' WHERE id=?", offer);
        confirm(offer, token, "100", "99", 422);
        assertThat(count("transfer_executions")).isZero();
        assertThat(count("base_transactions")).isZero();
        verifyNoInteractions(avenia, blindpay);
        observe("execucoes", 0);
    }

    @Test
    @DisplayName("P86-13 CHAIN-04 estados terminais e repeticao nao reabrem operacoes")
    void terminalStatesAndIdempotencyBoundImpact() throws Exception {
        mockPayout();
        UUID offer = offer(companyA, owner, beneficiary(companyA));
        confirm(offer, token, "100", "99", 200);
        UUID id = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        for (String terminal : List.of("COMPLETED", "FAILED", "REFUNDED")) {
            jdbc.update("UPDATE transfer_executions SET status=? WHERE id=?", terminal, id);
            String before = jdbc.queryForObject("SELECT row_to_json(t)::text FROM transfer_executions t WHERE id=?", String.class, id);
            processor.start(id);
            processor.reconcile(id);
            assertThatThrownBy(() -> processor.acceptRequote(id, companyA, new BigDecimal("100"), new BigDecimal("99")))
                    .isInstanceOf(ages.vstable.backend.exception.ConflictException.class);
            confirm(offer, token, "100", "99", 200);
            assertThat(jdbc.queryForObject("SELECT row_to_json(t)::text FROM transfer_executions t WHERE id=?", String.class, id)).isEqualTo(before);
        }
        verify(blindpay, times(1)).createEvmPayout(any(), anyString());
        assertThat(count("transfer_executions")).isEqualTo(1);
        assertThat(count("base_transactions")).isEqualTo(1);
        observe("payoutsSimulados", 1);
        observe("estadosTestadosSemReabrir", List.of("COMPLETED", "FAILED", "REFUNDED"));
    }

    @Test
    @DisplayName("P86-14 CHAIN-05 falsificacao HMAC e evento antigo nao alteram compliance")
    void webhookForgeryAndStaleSignaturesAreBlocked() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        http(webhook("impact-forged", timestamp, "{}").content("{\"changed\":true}"), 401, "POST /v1/webhooks/blindpay corpo adulterado assinatura [REDACTED]");
        http(post("/v1/webhooks/blindpay").contentType(MediaType.APPLICATION_JSON).content("{}"), 401, "POST /v1/webhooks/blindpay sem assinatura");
        http(webhook("impact-stale", timestamp - 601, "{}"), 401, "POST /v1/webhooks/blindpay assinatura antiga [REDACTED]");
        assertThat(count("blindpay_webhook_events")).isZero();
        verifyNoInteractions(avenia, blindpay);
        observe("eventosPersistidos", 0);
    }

    @Test
    @DisplayName("P86-15 CHAIN-05 replay ordem e empresa limitam alcance de webhook autentico")
    void signedEventsAreDeduplicatedOrderedAndScoped() throws Exception {
        jdbc.update("INSERT INTO provider_accounts(company_id,provider,external_customer_id,status) VALUES (?,'BLINDPAY','impact-customer','PENDING')", companyA);
        long timestamp = Instant.now().getEpochSecond();
        String approved = customerEvent(companyA, "approved", "2026-11-13T12:00:00Z");
        http(webhook("impact-new", timestamp, approved), 204, "POST /v1/webhooks/blindpay customer aprovado [REDACTED]");
        http(webhook("impact-new", timestamp, approved), 204, "POST /v1/webhooks/blindpay replay mesmo svix-id [REDACTED]");
        http(webhook("impact-old", timestamp, customerEvent(companyA, "rejected", "2026-11-13T11:00:00Z")), 204,
                "POST /v1/webhooks/blindpay evento fora de ordem [REDACTED]");
        http(webhook("impact-other-company", timestamp, customerEvent(companyB, "rejected", "2026-11-13T13:00:00Z")), 204,
                "POST /v1/webhooks/blindpay empresa divergente [REDACTED]");
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM provider_accounts", String.class)).isEqualTo("approved");
        assertThat(count("blindpay_webhook_events")).isEqualTo(3);
        observe("resultado", Map.of("requisicoes", 4, "idsUnicosPersistidos", 3, "kycFinal", "approved"));
        verifyNoInteractions(avenia, blindpay);
    }

    private JsonNode http(MockHttpServletRequestBuilder request, int expected, String step) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        observe(step, Map.of("httpStatus", response.getStatus()));
        assertThat(response.getStatus()).as(step).isEqualTo(expected);
        String body = response.getContentAsString();
        assertThat(body).doesNotContain("passwordHash", "passwordSalt", "twoFactorSecret", "stackTrace", signingSecret);
        return body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
    }

    @Test
    @DisplayName("P86-21 CHAIN-04 cotacao vence durante provisionamento mas payout simulado e enviado")
    void provisioningDelayCanExpireQuoteBeforeSubmission() throws Exception {
        mockPayout();
        UUID offer = offer(companyA, owner, beneficiary(companyA));
        confirm(offer, token, "100", "99", 200);
        UUID execution = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        jdbc.update("UPDATE transfer_executions SET status='REQUOTE_REQUIRED',external_payout_id=NULL,payout_quote_receiver_amount=98,"
                + "payout_quote_expires_at=now()+interval '5 minutes' WHERE id=?", execution);
        clearInvocations(blindpay);
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        java.time.Clock controlled = new java.time.Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(processor, "clock");
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "clock", controlled);
        when(provisioning.ensureManagedWallet(any(), any())).thenAnswer(call -> {
            time.set(time.get().plusSeconds(600));
            return new BlindPayProvisioningService.ManagedWallet("impact-wallet", "0x" + "1".repeat(40));
        });
        try {
            http(auth(post("/v1/transfer-executions/" + execution + "/requote/confirm"), token)
                    .contentType(MediaType.APPLICATION_JSON).content(amounts("100", "98")), 200,
                    "POST /v1/transfer-executions/EXEC_A/requote/confirm com relogio controlado");
            verify(blindpay).createEvmPayout(any(), anyString());
            assertThat(time.get()).isAfter(jdbc.queryForObject("SELECT payout_quote_expires_at FROM transfer_executions", OffsetDateTime.class).toInstant());
            observe("resultado", Map.of("avancoRelogioSegundos", 600, "payoutMockAposVencer", 1));
            observe("limite", "Clock controlado; nenhuma espera real ou prova de aceite de cotacao vencida pelo provedor");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(processor, "clock", original);
        }
    }

    @Test
    @DisplayName("P86-22 CHAIN-03 convite sobrevive a remocao do criador mas cadastro nao executa aceite")
    void pendingInvitationSurvivesCreatorRemovalButRegistrationDoesNotAcceptIt() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        JsonNode generated = createInvitation(email);
        UUID invitation = UUID.fromString(generated.path("invitation").path("id").asText());
        assertThat(generated.path("inviteUrl").asText()).isNotBlank();
        UUID creatorMembership = membershipId(owner);
        // Só OWNER pode convidar, mas a API protege OWNER contra remoção. Não simular um ataque SQL.
        JsonNode protectedOwner = http(auth(delete(membersUrl() + "/" + creatorMembership), token)
                .header("If-Match", "\"1\""), 409, "DELETE /members/OWNER protecao de proprietario");
        assertThat(protectedOwner.path("code").asText()).isEqualTo("OWNER_PROTECTED");
        jdbc.update("UPDATE company_memberships SET status='REMOVED',removed_at=now(),version=version+1 WHERE id=?",
                creatorMembership);
        assertThat(jdbc.queryForObject("SELECT status FROM company_invitations WHERE id=?", String.class, invitation))
                .isEqualTo("PENDING");
        http(auth(get(membersUrl() + "/me"), token), 200, "GET /members/me criador removido JWT anterior");
        http(auth(get(membersUrl()), token), 403, "GET /members criador removido JWT anterior");

        // Este é o cadastro público existente, não um endpoint inventado de aceite.
        JsonNode account = http(post("/v1/onboarding/account").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("fullName", "Convidado Sintetico", "email", email,
                        "password", password, "confirmPassword", password, "enableTwoFactor", false))),
                201, "POST /v1/onboarding/account email do convite [REDACTED]");
        UUID invitedUser = UUID.fromString(account.path("userId").asText());
        var login = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(200);
        String authorization = login.getHeader("Authorization");
        assertThat(authorization != null && authorization.startsWith("Bearer ")).isTrue();
        // Não registrar o cabeçalho, link, token ou dados da conta nas evidências.
        String invitedToken = authorization.substring("Bearer ".length());
        JsonNode me = http(auth(get("/v1/users/me"), invitedToken), 200, "GET /v1/users/me convidado apos login");
        assertThat(me.path("companyAccess").isNull()).isTrue();
        http(auth(get(membersUrl() + "/me"), invitedToken), 403, "GET /members/me convidado sem vinculo");
        http(auth(get("/v1/companies/" + companyA), invitedToken), 403, "GET /companies/A convidado sem vinculo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_memberships WHERE user_id=?",
                Integer.class, invitedUser)).isZero();
        assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE id=?", UUID.class, invitedUser)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM company_invitations WHERE id=?", String.class, invitation))
                .isEqualTo("PENDING");
        observe("precondicao", "Remocao do OWNER via fixture SQL; API devolve OWNER_PROTECTED; nao e exploracao SQL");
        observe("resultado", Map.of("convite", "PENDING", "cadastroHttp", 201, "loginHttp", 200,
                "novosVinculos", 0, "aceite", "NAO IMPLEMENTADO - ciclo completo nao demonstrado"));
        verifyNoInteractions(avenia, blindpay, supportChat);
    }

    @Test
    @DisplayName("P86-23 CHAIN-03 revogacao HTTP corta permissoes mas JWT permite consultar o proprio vinculo")
    void revokedOperatorKeepsIdentityAndSelfMembershipButLosesCompanyRoutes() throws Exception {
        UserEntity operator = user(companyA, CompanyRole.OPERATOR);
        String oldToken = jwt.generateToken(operator);
        UUID member = membershipId(operator);
        JsonNode invitation = createInvitation(UUID.randomUUID() + "@example.test");
        String invitationPath = "/v1/companies/" + companyA + "/invitations/"
                + invitation.path("invitation").path("id").asText();
        JsonNode before = http(auth(get(membersUrl() + "/me"), oldToken), 200, "GET /members/me OPERATOR ativo");
        assertThat(before.path("status").asText()).isEqualTo("ACTIVE");
        http(auth(get("/v1/companies/" + companyA), oldToken), 200, "GET /companies/A OPERATOR ativo");
        http(auth(delete(membersUrl() + "/" + member), token).header("If-Match", "\"1\""),
                204, "DELETE /members/OPERATOR OWNER autorizado");
        JsonNode removed = http(auth(get(membersUrl() + "/me"), oldToken), 200, "GET /members/me JWT anterior removido");
        assertThat(removed.path("status").asText()).isEqualTo("REMOVED");
        assertThat(removed.path("version").asLong()).isEqualTo(2);
        JsonNode identity = http(auth(get("/v1/users/me"), oldToken), 200, "GET /users/me JWT anterior removido");
        assertThat(identity.path("companyAccess").path("status").asText()).isEqualTo("REMOVED");
        for (String route : List.of(membersUrl(), "/v1/companies/" + companyB + "/members/me",
                invitationPath, invitationPath + "/link", "/v1/companies/" + companyA, "/v1/balance",
                "/api/transferencias", "/v1/dashboard/summary")) {
            JsonNode denied = http(auth(get(route), oldToken), 403, "GET " + aliasRoute(route) + " OPERATOR removido");
            assertThat(denied.path("code").asText()).isIn("COMPANY_ACCESS_REVOKED", "COMPANY_ACCESS_DENIED");
        }
        http(auth(post("/v1/companies/" + companyA + "/invitations"), oldToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Pessoa Sintetica","email":"blocked@example.test","role":"VIEWER"}
                        """), 403, "POST /invitations OPERATOR removido");
        http(auth(patch(membersUrl() + "/" + member), oldToken).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"), 403,
                "PATCH /members/self OPERATOR removido");
        http(auth(delete(membersUrl() + "/" + member), oldToken).header("If-Match", "\"2\""), 403,
                "DELETE /members/self OPERATOR removido");
        http(auth(post(invitationPath + "/regenerate"), oldToken).header("If-Match", "\"1\""), 403,
                "POST /invitations/CONVITE/regenerate OPERATOR removido");
        http(auth(delete(invitationPath), oldToken).header("If-Match", "\"1\""), 403,
                "DELETE /invitations/CONVITE OPERATOR removido");
        String expired = Jwts.builder().subject(operator.getEmail()).claim("account_type", "USER")
                .issuedAt(java.util.Date.from(Instant.now().minusSeconds(120)))
                .expiration(java.util.Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8))).compact();
        JsonNode expiredResponse = http(auth(get(membersUrl() + "/me"), expired), 401, "GET /members/me JWT expirado sintetico");
        assertThat(expiredResponse.path("code").asText()).isEqualTo("TOKEN_EXPIRED");
        assertThat(count("company_invitations")).isEqualTo(1);
        observe("limiteSessao", "JWT anterior ainda autentica identidade e proprio vinculo REMOVED; rotas empresariais negadas imediatamente; expirado retorna 401 sem esperar TTL");
        verifyNoInteractions(avenia, blindpay, supportChat);
    }

    @Test
    @DisplayName("P86-24 CHAIN-02 matriz GET cadastral Admin versus OWNER OPERATOR VIEWER de outra empresa")
    void catalogReadMatrixSeparatesPlatformAdminFromEveryCompanyRole() throws Exception {
        UserEntity other = user(companyB, CompanyRole.OWNER);
        UUID beneficiaryA = beneficiary(companyA);
        UUID beneficiaryB = beneficiary(companyB);
        String admin = adminToken();
        List<String> global = List.of("/v1/companies", "/v1/companies/summaries", "/v1/representatives",
                "/v1/beneficiaries", "/v1/beneficiaries/" + beneficiaryB);
        List<String> tenantB = List.of("/v1/companies/" + companyB,
                "/v1/companies/" + companyB + "/compliance-status",
                "/v1/companies/" + companyB + "/beneficiaries",
                "/v1/companies/" + companyB + "/beneficiaries/" + beneficiaryB);
        for (String route : global) {
            JsonNode result = http(auth(get(route), admin), 200, "GET " + aliasRoute(route) + " Admin");
            if (route.equals("/v1/companies") || route.equals("/v1/companies/summaries")) {
                assertThat(result.size()).isEqualTo(2);
                assertThat(result.toString()).contains(companyA.toString(), companyB.toString());
            }
            if (route.equals("/v1/representatives")) {
                assertThat(result.toString()).contains(owner.getId().toString(), other.getId().toString());
            }
            if (route.equals("/v1/beneficiaries")) {
                assertThat(result.path("content").size()).isEqualTo(2);
                assertThat(result.toString()).contains(beneficiaryA.toString(), beneficiaryB.toString());
            }
        }
        for (String route : tenantB) http(auth(get(route), admin), 200, "GET " + aliasRoute(route) + " Admin");
        JsonNode scoped = http(auth(get("/v1/companies/" + companyB + "/beneficiaries"), admin), 200,
                "GET /companies/B/beneficiaries Admin conteudo isolado");
        assertThat(scoped.toString()).contains(beneficiaryB.toString()).doesNotContain(beneficiaryA.toString());
        for (CompanyRole role : CompanyRole.values()) {
            String memberToken = role == CompanyRole.OWNER ? token : jwt.generateToken(user(companyA, role));
            for (String route : global) http(auth(get(route), memberToken), 403, "GET " + aliasRoute(route) + " " + role + " A");
            for (String route : tenantB) http(auth(get(route), memberToken), 403, "GET " + aliasRoute(route) + " " + role + " A");
            JsonNode own = http(auth(get("/v1/companies/" + companyA + "/beneficiaries"), memberToken), 200,
                    "GET /companies/A/beneficiaries " + role);
            assertThat(own.toString()).contains(beneficiaryA.toString()).doesNotContain(beneficiaryB.toString());
            http(auth(get("/v1/companies/" + companyA + "/beneficiaries/" + beneficiaryB), memberToken), 404,
                    "GET /companies/A/beneficiaries/BENEF_B " + role);
            http(auth(get("/v1/companies/" + companyA), memberToken), 200, "GET /companies/A " + role);
        }
        observe("classificacao", "Admin e conta separada sem vinculo: leitura autorizada; tres perfis empresariais nao leem cadastros B");
        verifyNoInteractions(avenia, blindpay, supportChat);
    }

    @Test
    @DisplayName("P86-25 CHAIN-02 matriz GET financeira mede rotas Admin e ignora companyId forjado pelo membro")
    void financialReadMatrixIncludesAdminRoutesAndRejectsTenantParameterOverride() throws Exception {
        UserEntity other = user(companyB, CompanyRole.OWNER);
        mockPayout();
        confirm(offer(companyB, other, beneficiary(companyB)), jwt.generateToken(other), "100", "99", 200);
        UUID transactionB = jdbc.queryForObject("SELECT id FROM base_transactions", UUID.class);
        UUID executionB = jdbc.queryForObject("SELECT id FROM transfer_executions", UUID.class);
        clearInvocations(blindpay, provisioning);
        String admin = adminToken();
        List<String> adminRoutes = List.of("/api/admin/transferencias", "/api/admin/transferencias/" + transactionB,
                "/v1/transfers", "/v1/transfers/" + transactionB);
        for (String route : adminRoutes) {
            JsonNode result = http(auth(get(route), admin), 200, "GET " + aliasRoute(route) + " Admin");
            assertThat(result.toString()).contains(transactionB.toString());
        }
        for (String metric : List.of("summary", "remittance-volume", "savings-history", "settlements", "transaction-volume")) {
            String route = "/v1/admin/dashboard/" + metric;
            http(auth(get(route), admin), 200, "GET " + route + " Admin global");
            http(auth(get(route).param("companyId", companyB.toString()), admin), 200, "GET " + route + " Admin empresa B");
        }
        for (CompanyRole role : CompanyRole.values()) {
            UserEntity member = role == CompanyRole.OWNER ? owner : user(companyA, role);
            String memberToken = role == CompanyRole.OWNER ? token : jwt.generateToken(member);
            jdbc.update("INSERT INTO avenia_kyc_verifications(user_id,status) VALUES (?,'APPROVED')", member.getId());
            for (String route : adminRoutes) http(auth(get(route), memberToken), 403, "GET " + aliasRoute(route) + " " + role + " A");
            for (String metric : List.of("summary", "remittance-volume", "savings-history", "settlements", "transaction-volume")) {
                http(auth(get("/v1/admin/dashboard/" + metric).param("companyId", companyB.toString()), memberToken),
                        403, "GET /v1/admin/dashboard/" + metric + " " + role + " A");
                String route = "/v1/dashboard/" + metric;
                JsonNode baseline = http(auth(get(route), memberToken), 200, "GET " + route + " " + role + " A");
                JsonNode injected = http(auth(get(route).param("companyId", companyB.toString()), memberToken),
                        200, "GET " + route + " companyId=B " + role + " A");
                assertThat(injected).isEqualTo(baseline);
            }
            JsonNode history = http(auth(get("/api/transferencias").param("companyId", companyB.toString()), memberToken),
                    200, "GET /api/transferencias companyId=B " + role + " A");
            assertThat(history.path("totalElements").asLong()).isZero();
            http(auth(get("/api/transferencias/" + transactionB), memberToken), 404,
                    "GET /api/transferencias/TX_B " + role + " A verificado");
            http(auth(get("/api/transferencias/" + transactionB + "/comprovante"), memberToken), 404,
                    "GET /api/transferencias/TX_B/comprovante " + role + " A verificado");
            http(auth(get("/v1/transfer-executions/" + executionB), memberToken), 404,
                    "GET /transfer-executions/EXEC_B " + role + " A");
        }
        for (String route : List.of("/api/transferencias", "/api/transferencias/" + transactionB,
                "/api/transferencias/" + transactionB + "/comprovante", "/v1/transfer-executions/" + executionB,
                "/v1/balance", membersUrl(), membersUrl() + "/me", "/v1/blindpay/compliance", "/v1/blindpay/compliance/rfi")) {
            http(auth(get(route), admin), 403, "GET " + aliasRoute(route) + " Admin sem vinculo");
        }
        for (String metric : List.of("summary", "remittance-volume", "savings-history", "settlements", "transaction-volume")) {
            http(auth(get("/v1/dashboard/" + metric), admin), 403, "GET /v1/dashboard/" + metric + " Admin sem vinculo");
        }
        observe("massa", Map.of("transacoesA", 0, "transacoesB", 1, "execucoesB", 1));
        observe("resultado", "Admin le historico e metricas nas rotas /admin e /v1/transfers; companyId=B nao amplia consultas dos membros A");
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);
    }

    @Test
    @DisplayName("P86-26 CHAIN-02 metadados e links de convite exigem OWNER da propria empresa inclusive contra Admin")
    void memberAndInvitationReadRoutesRequireOwnerOfTheNamedCompany() throws Exception {
        JsonNode invitation = createInvitation(UUID.randomUUID() + "@example.test");
        String path = "/v1/companies/" + companyA + "/invitations/" + invitation.path("invitation").path("id").asText();
        UserEntity otherOwner = user(companyB, CompanyRole.OWNER);
        for (String route : List.of(membersUrl(), path, path + "/link")) {
            http(auth(get(route), token), 200, "GET " + aliasRoute(route) + " OWNER A");
            http(auth(get(route), adminToken()), 403, "GET " + aliasRoute(route) + " Admin");
            http(auth(get(route), jwt.generateToken(otherOwner)), 403, "GET " + aliasRoute(route) + " OWNER B");
            for (CompanyRole role : List.of(CompanyRole.OPERATOR, CompanyRole.VIEWER)) {
                http(auth(get(route), jwt.generateToken(user(companyA, role))), 403,
                        "GET " + aliasRoute(route) + " " + role + " A");
            }
        }
        String crossPath = path.replace(companyA.toString(), companyB.toString());
        http(auth(get(crossPath), jwt.generateToken(otherOwner)), 404, "GET /companies/B/invitations/CONVITE_A OWNER B");
        http(auth(get(crossPath + "/link"), jwt.generateToken(otherOwner)), 404, "GET /companies/B/invitations/CONVITE_A/link OWNER B");
        verifyNoInteractions(avenia, blindpay, supportChat);
    }

    @Test
    @DisplayName("P86-27 CHAIN-02 listagens sem paginacao retornam toda massa apesar de size=1")
    void unpaginatedListsReturnAllSyntheticRowsWhilePagedEndpointsLimitResponse() throws Exception {
        for (int i = 0; i < 23; i++) company();
        for (int i = 0; i < 24; i++) user(companyA, CompanyRole.OPERATOR);
        for (int i = 0; i < 25; i++) beneficiary(companyA);
        String admin = adminToken();
        for (String route : List.of("/v1/companies", "/v1/companies/summaries", "/v1/representatives")) {
            var response = mvc.perform(auth(get(route).param("page", "0").param("size", "1"), admin))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode result = JSON.readTree(response.getContentAsString());
            assertThat(result.isArray()).isTrue();
            assertThat(result.size()).isEqualTo(25);
            assertThat(response.getContentAsString()).doesNotContain("passwordHash", "passwordSalt", "twoFactorSecret", signingSecret);
            observe("GET " + route + "?page=0&size=1", Map.of("httpStatus", 200, "registros", result.size(),
                    "bytesUtf8", response.getContentAsByteArray().length));
        }
        JsonNode beneficiaries = http(auth(get("/v1/companies/" + companyA + "/beneficiaries")
                .param("page", "0").param("size", "1"), admin), 200, "GET /companies/A/beneficiaries size=1 Admin");
        assertThat(beneficiaries.path("content").size()).isEqualTo(1);
        assertThat(beneficiaries.path("totalElements").asInt()).isEqualTo(25);
        JsonNode members = http(auth(get(membersUrl()).param("page", "0").param("size", "1"), token), 200,
                "GET /members size=1 OWNER A");
        assertThat(members.path("items").size()).isEqualTo(1);
        assertThat(members.path("totalElements").asInt()).isEqualTo(25);
        observe("paginados", Map.of("beneficiariosRetornados", 1, "beneficiariosTotal", 25, "membrosRetornados", 1, "membrosTotal", 25));
        observe("limite", "Massa pequena de 25 registros por listagem; sem carga, sem inferencia de latencia ou esgotamento. Membros paginados em memoria segundo revisao estatica");
        verifyNoInteractions(avenia, blindpay, supportChat);
    }

    private JsonNode createInvitation(String email) throws Exception {
        return http(auth(post("/v1/companies/" + companyA + "/invitations"), token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                        "name", "Convidado Sintetico", "email", email, "role", "OPERATOR"))),
                201, "POST /companies/A/invitations dados [REDACTED]");
    }

    private UUID membershipId(UserEntity user) {
        return jdbc.queryForObject("SELECT id FROM company_memberships WHERE user_id=?", UUID.class, user.getId());
    }

    private String membersUrl() { return "/v1/companies/" + companyA + "/members"; }

    private String aliasRoute(String route) {
        return route.replace(companyA.toString(), "EMPRESA_A").replace(companyB.toString(), "EMPRESA_B")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "REGISTRO");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private void confirm(UUID offer, String bearer, String source, String target, int expected) throws Exception {
        http(auth(post("/v1/quotes/offers/" + offer + "/confirm"), bearer).contentType(MediaType.APPLICATION_JSON)
                .content(amounts(source, target)), expected, "POST /v1/quotes/offers/OFERTA/confirm source=" + source + " target=" + target + " sem TOTP");
    }

    private String amounts(String source, String target) { return "{\"sourceAmount\":" + source + ",\"targetAmount\":" + target + "}"; }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void mockPayout() {
        when(provisioning.ensureManagedWallet(any(), any())).thenReturn(new BlindPayProvisioningService.ManagedWallet("impact-wallet", "0x" + "1".repeat(40)));
        var payout = mock(BlindPayPayoutResponse.class);
        when(payout.id()).thenReturn("impact-payout");
        when(payout.status()).thenReturn("completed");
        when(blindpay.createEvmPayout(any(), anyString())).thenReturn(payout);
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        String digits = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(100000000000L, 1000000000000L));
        for (int[] weights : List.of(new int[]{5,4,3,2,9,8,7,6,5,4,3,2}, new int[]{6,5,4,3,2,9,8,7,6,5,4,3,2})) {
            int sum = 0;
            for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
            digits += sum % 11 < 2 ? 0 : 11 - sum % 11;
        }
        jdbc.update("INSERT INTO companies(id,legal_name,cnpj) VALUES (?,'Empresa Sintetica',?)", id, digits);
        return id;
    }

    private UserEntity user(UUID company, CompanyRole role) {
        var user = users.saveAndFlush(UserEntity.builder().companyId(company).fullName("Pessoa Sintetica")
                .email(UUID.randomUUID() + "@example.test").passwordHash(passwords.encode(password))
                .passwordSalt("synthetic").createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        jdbc.update("INSERT INTO company_memberships(company_id,user_id,role,status) VALUES (?,?,?,'ACTIVE')", company, user.getId(), role.name());
        return user;
    }

    private String adminToken() {
        var admin = admins.saveAndFlush(AdministratorEntity.builder().fullName("Admin Sintetico").email(UUID.randomUUID() + "@example.test")
                .passwordHash("unused").passwordSalt("synthetic").accessLevel(AdminAccessLevel.SUPER_ADMIN)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
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

    private MockHttpServletRequestBuilder webhook(String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return post("/v1/webhooks/blindpay").contentType(MediaType.APPLICATION_JSON).header("svix-id", id)
                .header("svix-timestamp", timestamp).header("svix-signature", signature).content(body);
    }

    private String customerEvent(UUID company, String status, String updated) {
        return """
                {"webhook_event":"customer.update","id":"impact-customer","instance_id":"impact-synthetic-instance",
                "external_id":"%s","kyc_status":"%s","aml_status":"approved","updated_at":"%s"}
                """.formatted(company, status, updated);
    }
}
