package ages.vstable.backend.sandbox;

import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.*;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TotpService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;

@Tag("avenia-security-sandbox")
@EnabledIfEnvironmentVariable(named = "AVENIA_SECURITY_SANDBOX_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(named = "AVENIA_ASSESSMENT_PHASE", matches = "assessment|business")
@Testcontainers
@Import(AveniaSecuritySandboxTest.Transport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "transfers.reconciliation.enabled=false",
        "logging.level.root=OFF", "logging.level.ages.vstable.backend=OFF", "logging.level.org.springframework=OFF",
        "logging.level.org.hibernate=OFF", "logging.level.org.springframework.web=OFF",
        "logging.level.org.springframework.security=OFF", "logging.level.org.springframework.web.client=OFF",
        "spring.main.banner-mode=off", "app.invitation.frontend-origin=http://127.0.0.1"
})
class AveniaSecuritySandboxTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    static AveniaSandboxSession session;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        session = new AveniaSandboxSession();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("avenia.base-url", () -> AveniaSandboxSession.ORIGIN);
        r.add("avenia.api-key", () -> session.properties.getApiKey());
        r.add("avenia.private-key", () -> session.properties.getPrivateKey());
        r.add("blindpay.base-url", () -> "http://127.0.0.1:0");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Transport {
        @Bean @Primary RestClient.Builder boundedSandboxTransport() { return session.builder(); }
    }

    @Autowired AveniaClient avenia;
    @MockitoBean BlindPayClient blindpay;
    @MockitoBean ages.vstable.backend.external.ai.SupportChatClient supportChat;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Value("${local.server.port}") int port;
    private final HttpClient local = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    String password = UUID.randomUUID() + "Aa1!";
    UUID companyA, companyB, beneficiary, offer, execution;
    UserEntity userA, userB;
    String tokenA, tokenB;

    @Test
    void assessRealContractsAndLocalBoundariesWithDurableEvidence() throws Exception {
        AveniaSandboxSession s = session;
        try {
            prepareAndTestLocalBoundaries();
            if (s.phase.equals("business")) {
                String ownSub = s.json.readTree(Files.readString(Path.of(System.getenv("AVENIA_ASSESSMENT_OWN_RESOURCE_FILE"))))
                        .path("subaccount").asString();
                exerciseCompanyFlowOnOwnedSubaccount(ownSub);
                verifyNoInteractions(blindpay, supportChat);
                s.finish("FINISHED");
                if (s.cases.stream().anyMatch(c -> "FAIL".equals(c.get("status")))) {
                    throw new AssertionError("Security assertions failed; see sanitized assessment.json");
                }
                return;
            }
            int before = s.calls.size();
            JsonNode account = s.read("/v2/account/account-info");
            s.accountConfirmed = "900f6fe481e2ca36".equals(AveniaSandboxSession.fingerprint(account.path("id").asString()));
            if (!s.accountConfirmed) throw new IllegalStateException("Account mismatch");
            Map<String, Object> flags = AveniaSandboxSession.permissionFlags(s.read("/v2/account/access-info"));
            boolean canOperate = Boolean.FALSE.equals(flags.get("viewOnly")) && Boolean.TRUE.equals(flags.get("payIn"));
            s.record("TC-SBX-AV-01.6", canOperate ? "PASS" : "BLOQUEADO", "Flags de permissoes e conta antes de escrita",
                    "Conta confirmada, acesso operacional e flags conhecidas", "Flags coletadas por allowlist recursiva; complementa 01.2", "Cliente/signer reais", flags, before);
            JsonNode webhooks = s.read("/v2/notifications/webhooks/");
            if (!canOperate || !webhooks.path("webhooks").isArray() || webhooks.path("webhooks").size() != 0) {
                blockedFinancial("Permissao operacional ou ausencia de callbacks compartilhados nao confirmada");
                s.finish("FINISHED"); return;
            }
            s.writesAllowed = true;
            before = s.calls.size();
            String subAccount = avenia.criarSubconta("sec85-" + UUID.randomUUID()).getId();
            if (subAccount == null || subAccount.isBlank()) throw new IllegalStateException("Subaccount response missing id");
            // Recovery identifiers stay exclusively in the already ignored private key directory.
            Path recovery = Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"), "assessment-resources-" + Instant.now().toEpochMilli() + ".json");
            Files.writeString(recovery, s.json.writeValueAsString(Map.of("subaccount", subAccount)));
            s.evidence.put("created_resources", List.of("SUBCONTA_TESTE_A (INDIVIDUAL; permanente no provedor)"));
            JsonNode subMetadata = s.read("/v2/account/metadata?subAccountId=" + subAccount);
            boolean unlocked = subMetadata.path("brlUnlocked").asBoolean(false);
            s.record("TC-SBX-AV-02.4", "PASS", "Criacao de subconta sintetica propria",
                    "Cliente de producao cria subconta sem tocar em recursos existentes", "SUBCONTA_TESTE_A criada; nao houve submissao KYC",
                    "AveniaClient.criarSubconta real", Map.of("brl_unlocked", unlocked, "approved_kyc_fabricated", false), before);
            // These operations deliberately belong to the main sandbox account: client contract, not company E2E.
            verifyMainAccountQuotesAndTicket();
            if (unlocked) {
                exerciseCompanyFlowOnOwnedSubaccount(subAccount);
            } else {
                blockedBusiness("Subconta nova sem BRL liberado; KYC exige documentos nao fornecidos; nenhum documento inventado ou status aprovado artificialmente");
            }
            s.record("TC-SBX-AV-08.1", "NAO EXECUTADO", "Eventos recebidos, autenticidade, repeticao e ordem",
                    "Receptor Avenia e callback publico proprios", "HEAD sem receptor Avenia; nenhum tunel ou configuracao criada; polling nao e webhook",
                    "Inspecao estatica + limitacao de infraestrutura", Map.of(), s.calls.size());
            verifyNoInteractions(blindpay, supportChat);
            boolean failed = s.cases.stream().anyMatch(c -> "FAIL".equals(c.get("status")));
            s.finish("FINISHED");
            if (failed) throw new AssertionError("Security assertions failed; see sanitized assessment.json");
        } catch (Exception ex) {
            s.evidence.put("stopped_error_type", ex.getClass().getSimpleName());
            String field = AveniaSandboxSession.validationField(ex);
            if (field != null) s.evidence.put("sanitized_provider_validation_field", field);
            if (s.phase.equals("business")) blockedBusiness("Preparacao interrompida por rejeicao conhecida ou erro; consultar status HTTP e campo sanitizado, sem reenviar ticket");
            s.finish("INTERRUPTED");
            throw new AssertionError("Assessment interrupted; see sanitized evidence before any retry");
        }
    }

    private void verifyMainAccountQuotesAndTicket() throws Exception {
        var s = session;
        int before = s.calls.size();
        AveniaQuoteResponse source = avenia.createQuote(quote(new BigDecimal("10.00"), null));
        recordQuote("TC-SBX-AV-03.1", source, "SOURCE", new BigDecimal("10.00"), before);
        before = s.calls.size();
        AveniaQuoteResponse target = avenia.createQuote(quote(null, new BigDecimal("1.123456")));
        recordQuote("TC-SBX-AV-03.2", target, "TARGET", new BigDecimal("1.123456"), before);
        before = s.calls.size();
        // Documented simulated PIX input <=1000 BRL; INTERNAL output to self, never a real wallet.
        AveniaQuoteResponse fresh = avenia.createQuote(quote(new BigDecimal("10.00"), null));
        String reference = "sec85-" + UUID.randomUUID();
        AveniaTicketResponse ticket = avenia.createTicket(AveniaTicketRequest.builder().quoteToken(fresh.quoteToken())
                .externalId(reference).ticketBrlPixInput(new AveniaTicketRequest.BrlPixInput(null))
                .ticketBlockchainOutput(new AveniaTicketRequest.BlockchainOutput(new UUID(0, 0), null, null, null)).build());
        if (ticket.id() == null) throw new IllegalStateException("Ticket outcome unknown");
        Path recovery = Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"), "assessment-ticket-" + Instant.now().toEpochMilli() + ".json");
        Files.writeString(recovery, s.json.writeValueAsString(Map.of("ticket", ticket.id(), "external_reference", reference)));
        s.evidence.put("created_ticket", "TICKET_TESTE_MAIN_A; PIX simulado -> USDC INTERNAL propria conta");
        List<String> states = new ArrayList<>(); states.add(ticket.status());
        JsonNode current = null;
        for (int i = 0; i < 10; i++) {
            current = s.read("/v2/account/tickets/" + ticket.id());
            JsonNode view = current.has("ticket") ? current.path("ticket") : current;
            String state = view.path("status").asString("UNKNOWN");
            states.add(state);
            if (List.of("PAID", "FAILED", "PARTIAL-FAILED", "CANCELED").contains(state)) { current = view; break; }
            current = view;
            Thread.sleep(3000);
        }
        String last = states.getLast();
        boolean paid = "PAID".equals(last);
        s.record("TC-SBX-AV-04.1", paid ? "PASS" : "BLOQUEADO", "Ticket e acompanhamento de simulacao funcional documentada",
                "Uma operacao sintetica chega a PAID sem pagamento bancario", "Estados observados por consulta ativa; nenhuma notificacao recebida",
                "Cliente de producao cria ticket; GET de status via adaptador de teste",
                Map.of("synthetic_source_brl", "10.00", "states", states, "external_reference_matches", reference.equals(current.path("externalId").asString()),
                        "quote_token_exported", false), before);
        if (!paid && !List.of("FAILED", "PARTIAL-FAILED", "CANCELED").contains(last)) {
            s.halted = true; throw new IllegalStateException("Ticket result still unknown; no resubmission");
        }
        before = s.calls.size();
        JsonNode finalRead = s.read("/v2/account/tickets/" + ticket.id());
        if (finalRead.has("ticket")) finalRead = finalRead.path("ticket");
        s.record("TC-SBX-AV-06.2", last.equals(finalRead.path("status").asString()) ? "PASS" : "FAIL", "Estabilidade do estado final do ticket",
                "Consulta posterior preserva estado terminal", "Comparacao de duas consultas; nenhuma escrita adicional",
                "Avenia sandbox real; nao comprova idempotencia universal", Map.of("terminal_status", last), before);
    }

    private AveniaQuoteRequest quote(BigDecimal input, BigDecimal output) {
        return new AveniaQuoteRequest("BRL", "PIX", "USDC", "INTERNAL", input, output, false, false,
                null, null, null, null, null, null, null, "IMMEDIATE", null);
    }

    private void recordQuote(String id, AveniaQuoteResponse q, String side, BigDecimal fixed, int before) throws Exception {
        boolean valid = q.quoteToken() != null && !q.quoteToken().isBlank() && "BRL".equals(q.inputCurrency())
                && "USDC".equals(q.outputCurrency()) && q.inputAmount() != null && q.inputAmount().signum() > 0
                && q.outputAmount() != null && q.outputAmount().signum() > 0 && q.basePrice() != null && q.basePrice().signum() > 0
                && fixed.compareTo("SOURCE".equals(side) ? q.inputAmount() : q.outputAmount()) == 0;
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("data_kind", "synthetic quote values only"); details.put("amount_side", side);
        details.put("input_currency", q.inputCurrency()); details.put("output_currency", q.outputCurrency());
        details.put("input_amount", q.inputAmount()); details.put("output_amount", q.outputAmount());
        details.put("base_price", q.basePrice()); details.put("fee_settlement", q.feeSettlement());
        details.put("fees", q.appliedFees() == null ? List.of() : q.appliedFees().stream()
                .map(f -> Map.of("type", f.type(), "amount", f.amount(), "currency", f.currency())).toList());
        session.record(id, valid ? "PASS" : "FAIL", "Moedas, valores fixos, precisao, tarifas e contrato DTO",
                "Contrato consistente e lado fixo preservado", "Resposta tipada real; nao persistida por QuoteOrchestratorService",
                "AveniaClient.createQuote real; conta principal", details, before);
        if (!valid) throw new IllegalStateException("Unexpected quote contract");
    }

    private void blockedBusiness(String why) throws Exception {
        for (String id : List.of("TC-SBX-AV-03.3", "TC-SBX-AV-04.2", "TC-SBX-AV-05.2", "TC-SBX-AV-06.1")) {
            if (session.cases.stream().anyMatch(c -> id.equals(c.get("id")))) continue;
            session.record(id, "BLOQUEADO", "Oferta persistida/execucao empresarial/TOTP na confirmacao/repeticao da mesma oferta real",
                    "Subconta e beneficiario sinteticos habilitados; nenhum dado pessoal", why,
                    "Fluxo empresarial com provedor real nao exercitado", Map.of(), session.calls.size());
        }
    }

    private void blockedFinancial(String why) throws Exception { blockedBusiness(why); }

    private void prepareAndTestLocalBoundaries() throws Exception {
        companyA = company(); companyB = company();
        userA = user(companyA); userB = user(companyB);
        tokenA = login(userA); tokenB = login(userB);
        beneficiary = UUID.randomUUID(); offer = UUID.randomUUID(); execution = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO beneficiaries(id,company_id,nickname) VALUES (?,?,'Beneficiario Sintetico A')", beneficiary, companyA);
        jdbc.update("INSERT INTO quote_requests(id,company_id,requested_by,beneficiary_id,direction,source_currency,target_currency,source_payment_method,target_payment_method,requested_amount,amount_side,status) VALUES (?,?,?,?,'PAYOUT','BRL','USDC','PIX','INTERNAL',10,'SOURCE','COMPLETED')", request, companyA, userA.getId(), beneficiary);
        jdbc.update("INSERT INTO provider_quotes(id,quote_request_id,provider,idempotency_key,status,source_amount,target_amount,total_fee,expires_at) VALUES (?,?,'AVENIA',?,'SELECTED',10,1.123456,0,now()+interval '5 minutes')", offer, request, "local-fixture:" + offer);
        jdbc.update("INSERT INTO transfer_executions(id,provider_quote_id,company_id,requested_by,beneficiary_id,provider,status,source_currency,target_currency,source_amount,target_amount) VALUES (?,?,?,?,?,'AVENIA','PENDING_SUBMISSION','BRL','USDC',10,1.123456)", execution, offer, companyA, userA.getId(), beneficiary);
        int before = session.calls.size();
        int status = http("POST", "/v1/quotes", tokenB, Map.of("beneficiaryId", beneficiary, "direction", "PAYOUT", "sourceCurrency", "BRL", "targetCurrency", "USDC", "sourcePaymentMethod", "PIX", "targetPaymentMethod", "INTERNAL", "amount", new BigDecimal("10.00"), "amountSide", "SOURCE", "coverFees", false)).statusCode();
        localCase("TC-SBX-AV-02.1", status == 404, "Beneficiario A com sessao B", status, before);
        status = http("POST", "/v1/quotes/offers/" + offer + "/confirm", tokenB, Map.of("sourceAmount", "10.00", "targetAmount", "1.123456")).statusCode();
        localCase("TC-SBX-AV-02.2", status == 404, "Oferta A com sessao B", status, before);
        status = http("GET", "/v1/transfer-executions/" + execution, tokenB, null).statusCode();
        localCase("TC-SBX-AV-02.3", status == 404, "Execucao A com sessao B", status, before);
        status = http("POST", "/v1/transfers", tokenA, Map.of("beneficiaryId", beneficiary, "amount", "10.00", "amountType", "SOURCE", "destinationCurrency", "USDC", "paymentMethod", "PIX")).statusCode();
        localCase("TC-SBX-AV-05.1", status == 401 || status == 422, "Transferencia direta sem TOTP da operacao; login com TOTP ativo", status, before);
        boolean links = companyA.equals(jdbc.queryForObject("SELECT company_id FROM beneficiaries WHERE id=?", UUID.class, beneficiary))
                && companyA.equals(jdbc.queryForObject("SELECT company_id FROM transfer_executions WHERE id=?", UUID.class, execution));
        session.record("TC-SBX-AV-02.5", links && session.calls.size() == before ? "PASS" : "FAIL", "Ausencia de mutacao ou efeito externo apos BOLA",
                "Vinculos intactos e zero chamadas externas", "Verificacao SQL no PostgreSQL efemero; fixtures locais declaradas",
                "HTTP loopback real + filtros + controllers + banco Testcontainers", Map.of("links_unchanged", links), before);
    }

    private void localCase(String id, boolean success, String requirement, int status, int before) throws Exception {
        session.record(id, success && session.calls.size() == before ? "PASS" : "FAIL", requirement,
                "Rejeicao HTTP antes de chamada externa", "Resposta local registrada; massa sintetica sem recurso remoto nesta precondicao",
                "HTTP loopback real + seguranca/controller/servico + PostgreSQL efemero", Map.of("http_status", status, "remote_resources_used", false), before);
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        String cnpj = UUID.randomUUID().toString().replace("-", "").replaceAll("[a-f]", "1").substring(0, 14);
        jdbc.update("INSERT INTO companies(id,legal_name,cnpj) VALUES (?,'Empresa Sintetica SEC85',?)", id, cnpj);
        return id;
    }

    private UserEntity user(UUID company) {
        UserEntity user = users.saveAndFlush(UserEntity.builder().companyId(company).fullName("Pessoa Sintetica")
                .email(UUID.randomUUID() + "@example.test").passwordHash(passwords.encode(password)).passwordSalt("synthetic")
                .twoFactorEnabled(true).twoFactorSecret(totp.generateSecret()).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        jdbc.update("INSERT INTO company_memberships(company_id,user_id,role,status) VALUES (?,?,'OWNER','ACTIVE')", company, user.getId());
        return user;
    }

    private String login(UserEntity user) throws Exception {
        JsonNode challenge = session.json.readTree(http("POST", "/v1/auth/login", null, Map.of("email", user.getEmail(), "password", password)).body());
        byte[] secret = ReflectionTestUtils.invokeMethod(totp, "decodeBase32", user.getTwoFactorSecret());
        String code = ReflectionTestUtils.invokeMethod(totp, "generateCodeForWindow", secret, Instant.now().getEpochSecond() / 30);
        var response = http("POST", "/v1/auth/login/verify-2fa", null, Map.of("challengeToken", challenge.path("challengeToken").asString(), "code", code));
        JsonNode result = session.json.readTree(response.body());
        if (response.statusCode() != 200 || !result.hasNonNull("token")) throw new IllegalStateException("Synthetic local login failed");
        return result.path("token").asString();
    }

    private HttpResponse<String> http(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(35));
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(session.json.writeValueAsString(body)));
        return local.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void exerciseCompanyFlowOnOwnedSubaccount(String sub) throws Exception {
        var s = session;
        int before = s.calls.size();
        JsonNode account = s.read("/v2/account/account-info");
        s.accountConfirmed = "900f6fe481e2ca36".equals(AveniaSandboxSession.fingerprint(account.path("id").asString()));
        if (!s.accountConfirmed) throw new IllegalStateException("Account mismatch");
        JsonNode hooks = s.read("/v2/notifications/webhooks/");
        if (!hooks.path("webhooks").isArray() || !hooks.path("webhooks").isEmpty()) throw new IllegalStateException("Callback destinations changed");
        JsonNode metadata = s.read("/v2/account/metadata?subAccountId=" + sub);
        if (!metadata.path("brlUnlocked").asBoolean(false)) throw new IllegalStateException("Owned subaccount no longer unlocked");
        s.writesAllowed = true;
        // POLYGON is the functional route confirmed by this assessment; do not retry GNOSIS failures.
        avenia.createQuote(new AveniaQuoteRequest("BRL", "PIX", "USDC", "POLYGON", null, new BigDecimal("1.123456"),
                false, false, null, null, null, null, null, null, null, "IMMEDIATE", sub));
        byte[] addressBytes = new byte[20]; new java.security.SecureRandom().nextBytes(addressBytes);
        String wallet = "0x" + java.util.HexFormat.of().formatHex(addressBytes);
        AveniaBeneficiaryResponse remote = avenia.createWalletBeneficiary(new AveniaWalletBeneficiaryRequest(
                "SEC85-" + UUID.randomUUID(), "Own sandbox fixture", wallet, "POLYGON", null, true, null,
                "Pessoa Sintetica SEC85", "BR", null, sub));
        if (remote.id() == null) throw new IllegalStateException("Beneficiary response missing id");
        Files.writeString(Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"), "business-resources-" + Instant.now().toEpochMilli() + ".json"),
                s.json.writeValueAsString(Map.of("subaccount", sub, "beneficiary", remote.id(), "wallet", wallet)));
        jdbc.update("INSERT INTO avenia_kyc_verifications(id,user_id,avenia_sub_account_id,status,response_payload,created_at,updated_at) VALUES (?,?,?,'PENDING','{}',now(),now())",
                UUID.randomUUID(), userA.getId(), sub);
        jdbc.update("UPDATE beneficiaries SET receiving_method='CRYPTO_WALLET',avenia_id=?,avenia_wallet_id=?,avenia_sub_account_id=?,blockchain_network='polygon',wallet_address=?,currency='USDC' WHERE id=?",
                remote.id(), remote.id(), sub, wallet, beneficiary);
        s.evidence.put("created_resources", List.of("BENEFICIARIO_WALLET_TESTE_A na SUBCONTA_TESTE_A; endereco aleatorio nao financiado"));
        s.record("TC-SBX-AV-02.6", "PASS", "Vinculo de recurso remoto sintetico a empresa A",
                "Beneficiario proprio cadastrado na subconta correta e vinculado localmente", "Recurso novo registrado; nenhum recurso antigo alterado; KYC local permanece PENDING",
                "AveniaClient.createWalletBeneficiary real + PostgreSQL efemero", Map.of("subaccount_unlocked", true, "kyc_onboarding_executed", false), before);
        before = s.calls.size();
        Map<String, Object> request = Map.of("beneficiaryId", beneficiary, "direction", "PAYOUT", "sourceCurrency", "BRL", "targetCurrency", "USDC",
                "sourcePaymentMethod", "PIX", "targetPaymentMethod", "POLYGON", "amount", new BigDecimal("1.123456"), "amountSide", "TARGET", "coverFees", false,
                "description", "sec85-business-" + UUID.randomUUID());
        var denied = http("POST", "/v1/quotes", tokenB, request);
        s.record("TC-SBX-AV-02.7", denied.statusCode() == 404 && s.calls.size() == before ? "PASS" : "FAIL", "BOLA com beneficiario remoto proprio de A",
                "B nao utiliza o beneficiario A e nenhum GET/POST vai ao provedor", "Resposta HTTP e contador verificados",
                "HTTP local real; beneficiario remoto ja cadastrado", Map.of("http_status", denied.statusCode()), before);
        var response = http("POST", "/v1/quotes", tokenA, request);
        if (response.statusCode() != 200) {
            s.record("TC-SBX-AV-03.3", "BLOQUEADO", "Oferta real persistida", "Oferta empresarial em provider_quotes",
                    "API local nao forneceu oferta executavel; nao criar ticket independente para mascarar o bloqueio", "HTTP local + provedor real",
                    Map.of("http_status", response.statusCode()), before);
            blockedBusiness("API local nao forneceu oferta executavel; provider_quotes registra a falha do provedor. Nenhum ticket empresarial criado");
            return;
        }
        JsonNode body = s.json.readTree(response.body()).path("offer");
        UUID realOffer = UUID.fromString(body.path("offerId").asString());
        BigDecimal source = new BigDecimal(body.path("sourceAmount").asString());
        BigDecimal target = new BigDecimal(body.path("targetAmount").asString());
        BigDecimal persisted = jdbc.queryForObject("SELECT target_amount FROM provider_quotes WHERE id=?", BigDecimal.class, realOffer);
        s.record("TC-SBX-AV-03.3", persisted.compareTo(target) == 0 ? "PASS" : "FAIL", "Oferta, moeda, valores e persistencia",
                "Resposta HTTP e provider_quotes preservam o montante cripto", "Cotacao via QuoteOrchestratorService/AveniaQuoteProvider reais; sem alterar valores confirmados",
                "HTTP local + provedor real + PostgreSQL", Map.of("synthetic_source_brl", source, "synthetic_target_usdc", target, "stored_target_usdc", persisted), before);
        before = s.calls.size();
        var foreignConfirm = http("POST", "/v1/quotes/offers/" + realOffer + "/confirm", tokenB, Map.of("sourceAmount", source, "targetAmount", target));
        s.record("TC-SBX-AV-02.8", foreignConfirm.statusCode() == 404 && s.calls.size() == before ? "PASS" : "FAIL", "BOLA de oferta obtida de cotacao real",
                "Rejeitar B antes da Avenia", "Oferta real de A nao confirmada por B", "HTTP local + PostgreSQL; zero chamadas novas ao provedor",
                Map.of("http_status", foreignConfirm.statusCode()), before);
        before = s.calls.size();
        var confirmation = http("POST", "/v1/quotes/offers/" + realOffer + "/confirm", tokenA, Map.of("sourceAmount", source, "targetAmount", target));
        UUID realExecution = jdbc.queryForObject("SELECT id FROM transfer_executions WHERE provider_quote_id=?", UUID.class, realOffer);
        String ticketId = jdbc.queryForObject("SELECT external_ticket_id FROM transfer_executions WHERE id=?", String.class, realExecution);
        String localStatus = jdbc.queryForObject("SELECT status FROM transfer_executions WHERE id=?", String.class, realExecution);
        boolean submitted = ticketId != null;
        s.record("TC-SBX-AV-05.2", submitted ? "FAIL" : "BLOQUEADO", "TOTP da operacao na confirmacao da oferta",
                "Sem TOTP da transferencia, bloquear antes de criar ticket", submitted ? "Usuario com TOTP ativo e login MFA criou ticket sem TOTP da operacao" : "Nenhum ticket criado; verificar recotacao/preco antes de concluir efetividade do fator",
                "Endpoint HTTP real + Avenia real", Map.of("http_status", confirmation.statusCode(), "ticket_created", submitted, "local_status", localStatus), before);
        if (!submitted) {
            s.record("TC-SBX-AV-04.2", "BLOQUEADO", "Execucao e preco fresco", "Um ticket empresarial com preco protegido", "Nenhum ticket retornado; nao reenviar com outra cotacao",
                    "HTTP local; resultado registrado sem nova tentativa", Map.of("local_status", localStatus), s.calls.size());
            return;
        }
        Files.writeString(Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"), "business-ticket-" + Instant.now().toEpochMilli() + ".json"),
                s.json.writeValueAsString(Map.of("subaccount", sub, "ticket", ticketId)));
        before = s.calls.size();
        var repeat = http("POST", "/v1/quotes/offers/" + realOffer + "/confirm", tokenA, Map.of("sourceAmount", source, "targetAmount", target));
        boolean same = realExecution.toString().equals(s.json.readTree(repeat.body()).path("executionId").asString());
        s.record("TC-SBX-AV-06.1", same && s.calls.size() == before ? "PASS" : "FAIL", "Confirmacao repetida da mesma oferta empresarial",
                "Mesma execucao e zero novas chamadas externas", "Repeticao HTTP e quantidade de registros verificadas; nao prova idempotencia universal do provedor",
                "HTTP local + PostgreSQL + contador de fronteira", Map.of("same_execution", same, "execution_count", jdbc.queryForObject("SELECT count(*) FROM transfer_executions WHERE provider_quote_id=?", Integer.class, realOffer)), before);
        String terminal = "UNKNOWN";
        before = s.calls.size();
        for (int i = 0; i < 10; i++) {
            JsonNode ticket = s.read("/v2/account/tickets/" + ticketId + "?subAccountId=" + sub);
            if (ticket.has("ticket")) ticket = ticket.path("ticket");
            terminal = ticket.path("status").asString("UNKNOWN");
            if (List.of("PAID", "FAILED", "PARTIAL-FAILED", "CANCELED").contains(terminal)) break;
            Thread.sleep(3000);
        }
        if (!List.of("PAID", "FAILED", "PARTIAL-FAILED", "CANCELED").contains(terminal)) {
            s.halted = true; throw new IllegalStateException("Company ticket outcome unknown; stop without resubmission");
        }
        s.record("TC-SBX-AV-04.2", "PAID".equals(terminal) ? "PASS" : "BLOQUEADO", "Acompanhamento do ticket empresarial",
                "PIX simulado leva ticket proprio a PAID", "Acompanhamento ativo na subconta; nenhum webhook recebido",
                "Avenia real; consulta com subAccountId", Map.of("provider_terminal_status", terminal, "local_status", localStatus), before);
        BigDecimal historical = jdbc.queryForObject("SELECT b.foreign_amount FROM base_transactions b JOIN transfer_executions e ON e.transaction_id=b.id WHERE e.id=?", BigDecimal.class, realExecution);
        BigDecimal executed = jdbc.queryForObject("SELECT target_amount FROM transfer_executions WHERE id=?", BigDecimal.class, realExecution);
        s.record("TC-SBX-AV-03.4", historical.compareTo(executed) == 0 ? "PASS" : "FAIL", "Precisao entre execucao e historico cripto",
                "Historico preserva montante efetivo da execucao", "Comparacao SQL de montantes sinteticos originados na resposta real",
                "Avenia real + persistencia de producao", Map.of("executed_target_usdc", executed, "historical_target_usdc", historical), s.calls.size());
        var localRead = http("GET", "/v1/transfer-executions/" + realExecution, tokenA, null);
        String observedLocal = s.json.readTree(localRead.body()).path("status").asString();
        s.record("TC-SBX-AV-06.3", "PAID".equals(terminal) && !"COMPLETED".equals(observedLocal) ? "FAIL" : "PASS", "Sincronizacao do estado local com o provedor",
                "Operacao paga e refletida no estado local por mecanismo de acompanhamento", "GET local comparado ao ticket final; nenhum status foi atualizado artificialmente",
                "HTTP local + estado Avenia real; polling do teste nao concilia a aplicacao", Map.of("provider_status", terminal, "local_status", observedLocal), s.calls.size());
    }

}
