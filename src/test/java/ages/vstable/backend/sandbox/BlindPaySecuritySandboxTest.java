package ages.vstable.backend.sandbox;

import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.*;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.*;
import ages.vstable.backend.service.TotpService;
import ages.vstable.backend.service.transfer.BlindPayExecutionProcessor;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
import tools.jackson.databind.json.JsonMapper;

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
import java.time.ZoneOffset;
import java.util.*;

/** Real sandbox opt-in; each subcase has an independent, allowlisted result. No production changes. */
@Tag("blindpay-security-sandbox")
@EnabledIfEnvironmentVariable(named = "BLINDPAY_SECURITY_SANDBOX_ENABLED", matches = "true")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "transfers.reconciliation.enabled=false",
        "quotes.provider-timeout=90s", "logging.level.root=OFF", "logging.level.ages.vstable.backend=OFF",
        "logging.level.org.springframework=OFF", "logging.level.org.springframework.web=OFF",
        "logging.level.org.springframework.security=OFF", "logging.level.org.springframework.web.client=OFF",
        "logging.level.org.hibernate=OFF", "spring.main.banner-mode=off",
        "avenia.base-url=http://127.0.0.1:1", "avenia.api-key=", "avenia.private-key=",
        "ai.support.api-key=", "ai.support.gemini-base-url=http://127.0.0.1:1",
        "ai.support.deepseek-base-url=http://127.0.0.1:1"
})
@Import(BlindPaySecuritySandboxTest.OutboundConfiguration.class)
class BlindPaySecuritySandboxTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class OutboundConfiguration {
        @Bean BlindPaySandboxBoundary boundary(BlindPayProperties properties) {
            return new BlindPaySandboxBoundary(properties.getInstanceId());
        }
        @Bean @Primary RestClient.Builder sandboxRestClientBuilder(BlindPaySandboxBoundary boundary) {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10)).build();
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(30));
            return RestClient.builder().requestFactory(factory).requestInterceptor(boundary);
        }
    }

    @MockitoBean private AveniaClient avenia;
    @Autowired private BlindPaySandboxBoundary boundary;
    @Autowired private BlindPayClient client;
    @Autowired private BlindPayProperties properties;
    @Autowired private RestClient.Builder sandboxRestClientBuilder;
    @Autowired private BlindPayExecutionProcessor processor;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private BeneficiaryRepository beneficiaries;
    @Autowired private ProviderAccountRepository accounts;
    @Autowired private BeneficiaryProviderAccountRepository beneficiaryAccounts;
    @Autowired private ProviderQuoteRepository offers;
    @Autowired private TransferExecutionRepository executions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenUtils jwt;
    @Autowired private PasswordEncoder encoder;
    @Autowired private TotpService totp;
    @Value("${local.server.port}") private int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient localHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> resources = new ArrayList<>();
    private final String password = UUID.randomUUID().toString();
    private String tokenA;
    private String tokenB;
    private String customerId;
    private String bankId;
    private String failure;
    private UserEntity owner;
    private CompanyEntity company;
    private BeneficiaryEntity beneficiary;
    private Instant started;
    private boolean finished;

    @Test
    void controlledAssessment() throws Exception {
        started = Instant.now();
        boundary.checkpoint(() -> {
            try { export(); }
            catch (Exception ex) { throw new IllegalStateException("Could not persist sanitized HTTP checkpoint"); }
        });
        try {
            authenticateAndPrepare();
            setupLocalAccountsAndLogin();
            isolation();
            scenario("TC-SBX-BP-04-success", () -> transfer("SOURCE", "100.00", TransferExecutionStatus.COMPLETED, false, true));
            scenario("TC-SBX-BP-04-payin-failed", () -> transfer("SOURCE", "666.00", TransferExecutionStatus.FAILED, false, false));
            scenario("TC-SBX-BP-04-payin-refunded", () -> transfer("SOURCE", "777.00", TransferExecutionStatus.REFUNDED, false, false));
            scenario("TC-SBX-BP-04-payout-failed", () -> transfer("TARGET", "666.00", TransferExecutionStatus.FAILED, false, false));
            scenario("TC-SBX-BP-04-payout-refunded", () -> transfer("TARGET", "777.00", TransferExecutionStatus.REFUNDED, false, false));
            scenario("TC-SBX-BP-04-expired", this::expiredOffer);
            scenario("TC-SBX-BP-04-requote", () -> transfer("SOURCE", "100.00", TransferExecutionStatus.COMPLETED, true, false));
            scenario("TC-SBX-BP-04-idempotency", () -> transfer("SOURCE", "100.00", TransferExecutionStatus.COMPLETED, false, true));
        } catch (Exception ex) {
            failure = ex.getClass().getSimpleName();
            record("TC-SBX-BP-preparation", "BLOQUEADO", "Required preparation completed",
                    "Preparation stopped: " + (ex instanceof SafeAbort ? ex.getMessage() : failure), 0, Map.of());
        } finally {
            finished = true;
            record("TC-SBX-BP-webhook-real", "NÃO EXECUTADO", "Real event received and authenticated", "No authorized public receiver", 0, Map.of());
            record("TC-SBX-AV", "NÃO EXECUTADO", "Avenia sandbox exercised", "Outside this execution", 0, Map.of());
            export();
        }
        long failed = results.stream().filter(r -> "FAIL".equals(r.get("status"))).count();
        boolean blocked = results.stream().anyMatch(r -> "BLOQUEADO".equals(r.get("status")));
        if (failed > 0 || blocked) {
            throw new AssertionError("Sandbox assessment has " + failed + " FAIL; blocked=" + blocked
                    + ". See sanitized assessment.json. No provider payloads in this assertion.");
        }
    }

    private void authenticateAndPrepare() throws Exception {
        boundary.scenario("TC-SBX-BP-01");
        RestClient direct = sandboxRestClientBuilder.clone().build();
        String root = "https://api.blindpay.com/v1/instances/" + properties.getInstanceId();
        String authorization = "Bearer " + properties.getApiKey();
        JsonNode instance = json.readTree(direct.get().uri(root).header("Authorization", authorization)
                .retrieve().body(String.class));
        require(instance.get("id").asString().equals(properties.getInstanceId())
                && "development".equals(instance.get("type").asString()), "DEVELOPMENT_REQUIRED");
        boundary.developmentConfirmed();
        record("TC-SBX-BP-01-instance", "PASS", "Authenticated Development instance", "Development confirmed by API", 1, Map.of());

        record("TC-SBX-BP-02-new-customer", "BLOQUEADO", "New synthetic customer", "Current contract requires browser ToS and document fixtures; no bypass attempted", 0, Map.of());
        boundary.scenario("TC-SBX-BP-02-preparation");
        String listBody = direct.get().uri(root + "/customers").header("Authorization", authorization)
                .retrieve().body(String.class);
        JsonNode list = json.readTree(listBody);
        require(list.isArray(), "CUSTOMER_LIST_CONTRACT");
        String configuredCustomer = System.getenv("BLINDPAY_SANDBOX_CUSTOMER_ID");
        for (JsonNode candidate : list) {
            require(candidate.has("email") && candidate.get("email").asString().matches(".*@example\\.(com|test)$"), "NON_SYNTHETIC_CUSTOMER_STOP");
            if (!candidate.get("email").asString().endsWith("@example.com")) { continue; }
            if (configuredCustomer != null && !configuredCustomer.equals(candidate.get("id").asString())) { continue; }
            if (candidate.has("kyc_status") && "approved".equals(candidate.get("kyc_status").asString())) {
                customerId = candidate.get("id").asString(); break;
            }
        }
        require(customerId != null, "APPROVED_DEMO_CUSTOMER_REQUIRED");
        BlindPayCustomerResponse customer = client.getCustomer(customerId);
        require(customerId.equals(customer.id()) && "approved".equals(customer.kycStatus())
                && Boolean.TRUE.equals(customer.isTosAccepted()), "CUSTOMER_NOT_READY");
        require(customer.instanceId() == null || customer.instanceId().equals(properties.getInstanceId()), "CUSTOMER_INSTANCE_MISMATCH");
        resources.add(Map.of("alias", "CUSTOMER_A", "origin", "existing_demo", "modified", false,
                "customer_type", customer.type(), "kyc_status", "approved", "aml_status", customer.amlStatus() == null ? "not_screened" : customer.amlStatus(), "terms_accepted", true));
        record("TC-SBX-BP-01-client-contract", "PASS", "Client parses approved demo customer and accepted ToS", "KYC approved and ToS accepted; AML not asserted", 2, Map.of("aml_clear_demonstrated", "clear".equals(customer.amlStatus())));
        JsonNode spec = json.readTree(Files.readString(Path.of("build/security-sandbox/blindpay-openapi.json")));
        JsonNode examples = spec.get("components").get("schemas").get("CreateBankAccountIn").get("properties");
        BlindPayBankAccountResponse bank = client.createBankAccount(customerId, BlindPayCreateBankAccountRequest.builder()
                .type(BlindPayApi.BankAccount.Type.wire).name("SEC85 Sandbox " + Instant.now().getEpochSecond())
                .beneficiaryName("Synthetic Sandbox Beneficiary").recipientRelationship("vendor_or_supplier")
                .accountClass(BlindPayApi.BankAccount.AccountClass.individual)
                .accountType(BlindPayApi.BankAccount.AccountType.checking)
                .routingNumber(examples.get("routing_number").get("example").asString())
                .accountNumber(examples.get("account_number").get("example").asString())
                .addressLine1("Synthetic Sandbox Address").city("Sandbox").stateProvinceRegion("CA")
                .country("US").postalCode("94105").build(), "sec85-bank-" + UUID.randomUUID());
        bankId = bank.id();
        require(bankId != null && "approved".equalsIgnoreCase(bank.status()), "BANK_NOT_APPROVED");
        BlindPayBankAccountResponse verified = client.getBankAccount(customerId, bankId);
        require(bankId.equals(verified.id()) && "approved".equalsIgnoreCase(verified.status()), "BANK_LINK_NOT_VERIFIED");
        resources.add(Map.of("alias", "BANK_ACCOUNT_A", "origin", "created_this_run", "rail", "wire", "status", "approved"));
        record("TC-SBX-BP-02-provider-association", "PASS", "Bank belongs to approved demo customer", "Created and fetched through CUSTOMER_A path", 2, Map.of());
    }

    private void setupLocalAccountsAndLogin() throws Exception {
        company = companies.saveAndFlush(CompanyEntity.builder().legalName("Synthetic Sandbox A")
                .cnpj(syntheticCnpj()).country("Brasil")
                .zipCode("00000000").state("RS").build());
        owner = user(company, true);
        CompanyEntity other = companies.saveAndFlush(CompanyEntity.builder().legalName("Synthetic Sandbox B")
                .cnpj(syntheticCnpj()).country("Brasil")
                .zipCode("00000000").state("RS").build());
        UserEntity userB = user(other, false);
        tokenB = login(userB, false);
        tokenA = login(owner, true);
        beneficiary = beneficiaries.saveAndFlush(BeneficiaryEntity.builder().company(company)
                .nickname("Synthetic Sandbox Beneficiary").accountHolderName("Synthetic Sandbox Beneficiary")
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT).currency("USD").paymentRail("wire").build());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        accounts.saveAndFlush(ProviderAccountEntity.builder().company(company).provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId(customerId).status("APPROVED").createdAt(now).updatedAt(now).build());
        beneficiaryAccounts.saveAndFlush(BeneficiaryProviderAccountEntity.builder().beneficiary(beneficiary)
                .provider(IntegrationProvider.BLINDPAY).externalBankAccountId(bankId).status("APPROVED")
                .createdAt(now).updatedAt(now).build());
    }

    private UserEntity user(CompanyEntity targetCompany, boolean twoFactor) {
        UserEntity user = users.saveAndFlush(UserEntity.builder().companyId(targetCompany.getId())
                .fullName("Synthetic Sandbox User").email(UUID.randomUUID() + "@example.test")
                .passwordHash(encoder.encode(password)).passwordSalt("unused-synthetic-salt")
                .twoFactorEnabled(false).build());
        jdbc.update("INSERT INTO company_memberships(company_id,user_id,role,status) VALUES (?,?,'OWNER','ACTIVE')",
                targetCompany.getId(), user.getId());
        return user;
    }

    private String syntheticCnpj() {
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

    private String login(UserEntity user, boolean enableTwoFactor) throws Exception {
        if (enableTwoFactor) {
            String temporary = jwt.generateToken(user);
            require(local("POST", "/v1/auth/2fa/setup", "{}", temporary).statusCode() == 200, "TOTP_SETUP");
            String secret = users.findById(user.getId()).orElseThrow().getTwoFactorSecret();
            byte[] decoded = ReflectionTestUtils.invokeMethod(totp, "decodeBase32", secret);
            long window = Instant.now().getEpochSecond() / 30;
            String activation = ReflectionTestUtils.invokeMethod(totp, "generateCodeForWindow", decoded, window);
            require(local("POST", "/v1/auth/2fa/activate", json.writeValueAsString(Map.of("code", activation)), temporary)
                    .statusCode() == 200, "TOTP_ACTIVATE");
            var challenge = local("POST", "/v1/auth/login", json.writeValueAsString(Map.of("email", user.getEmail(), "password", password)), null);
            require(challenge.statusCode() == 200 && challenge.headers().firstValue("Authorization").isEmpty(), "TOTP_CHALLENGE");
            String code = ReflectionTestUtils.invokeMethod(totp, "generateCodeForWindow", decoded, window + 1);
            var verified = local("POST", "/v1/auth/login/verify-2fa", json.writeValueAsString(Map.of(
                    "challengeToken", json.readTree(challenge.body()).get("challengeToken").asString(), "code", code)), null);
            require(verified.statusCode() == 200, "TOTP_LOGIN");
            record("TC-SBX-BP-04-login", "PASS", "Account has active TOTP and authenticated session", "Setup, activation, challenge and login via loopback HTTP", 0, Map.of());
            return verified.headers().firstValue("Authorization").orElseThrow().substring(7);
        }
        var response = local("POST", "/v1/auth/login", json.writeValueAsString(Map.of("email", user.getEmail(), "password", password)), null);
        require(response.statusCode() == 200, "LOGIN_B");
        return response.headers().firstValue("Authorization").orElseThrow().substring(7);
    }

    private void isolation() throws Exception {
        boundary.scenario("TC-SBX-BP-02-isolation");
        int before = boundary.count();
        var response = local("POST", "/v1/quotes", quoteBody("SOURCE", "100.00"), tokenB);
        check("TC-SBX-BP-02-foreign-beneficiary", response.statusCode() == 404 && boundary.count() == before,
                "Foreign beneficiary rejected before provider call", "HTTP " + response.statusCode(), before, Map.of("external_calls_delta", boundary.count() - before));
    }

    private void transfer(String side, String amount, TransferExecutionStatus expected, boolean forceExpiry, boolean duplicate) throws Exception {
        int before = boundary.count();
        QuoteResponse quote = quote(side, amount);
        verifyQuote(quote, side, amount);
        int beforeForeign = boundary.count();
        var foreign = local("POST", confirmPath(quote), confirmation(quote), tokenB);
        check(boundaryId() + "-foreign-offer", foreign.statusCode() == 404 && boundary.count() == beforeForeign,
                "Foreign offer rejected without external call", "HTTP " + foreign.statusCode(), beforeForeign, Map.of());
        int beforeConfirm = boundary.count();
        var confirmed = local("POST", confirmPath(quote), confirmation(quote), tokenA);
        require(confirmed.statusCode() == 200, "CONFIRM_HTTP_" + confirmed.statusCode());
        UUID id = UUID.fromString(json.readTree(confirmed.body()).get("executionId").asString());
        TransferExecutionEntity execution = executions.findById(id).orElseThrow();
        check(boundaryId() + "-totp", boundary.count() == beforeConfirm,
                "Confirmation without TOTP must not trigger provider", "HTTP 200; provider_calls=" + (boundary.count() - beforeConfirm), beforeConfirm,
                Map.of("totp_enabled", Boolean.TRUE.equals(users.findById(owner.getId()).orElseThrow().getTwoFactorEnabled()), "totp_sent", false));
        if (forceExpiry) {
            execution.setPayoutQuoteExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
            executions.saveAndFlush(execution);
        }
        if (duplicate) {
            int duplicateBefore = boundary.count();
            var again = local("POST", confirmPath(quote), confirmation(quote), tokenA);
            boolean same = again.statusCode() == 200 && id.toString().equals(json.readTree(again.body()).get("executionId").asString());
            check(boundaryId() + "-repeat-confirm", same && boundary.count() == duplicateBefore,
                    "Same execution and no extra provider call", "HTTP " + again.statusCode(), duplicateBefore, Map.of());
            if (execution.getExternalPayinId() != null) {
                var retried = client.createEvmPayin(execution.getExternalPayinQuoteId(), "transfer-execution:" + id + ":payin");
                check(boundaryId() + "-provider-idempotency", execution.getExternalPayinId().equals(retried.id()),
                        "Same payin for same idempotency key", "Payin identity compared in memory", duplicateBefore, Map.of());
            }
        }
        List<String> observed = new ArrayList<>();
        boolean confirmedRequote = false;
        for (int poll = 0; poll < 10; poll++) {
            execution = executions.findById(id).orElseThrow();
            if (observed.isEmpty() || !observed.getLast().equals(execution.getStatus().name())) { observed.add(execution.getStatus().name()); }
            if (execution.getStatus().isTerminal()) { break; }
            require(!boundary.halted() && !uncertain(execution), "UNRESOLVED_PROVIDER_RESULT");
            if (execution.getStatus() == TransferExecutionStatus.REQUOTE_REQUIRED) {
                var response = local("POST", "/v1/transfer-executions/" + id + "/requote/confirm",
                        json.writeValueAsString(Map.of("sourceAmount", execution.getSourceAmount(), "targetAmount", execution.getPayoutQuoteReceiverAmount())), tokenA);
                require(response.statusCode() == 200, "REQUOTE_HTTP_" + response.statusCode());
                confirmedRequote = true;
            } else {
                BlindPaySandboxBoundary.pause(10_000);
                processor.reconcile(id);
            }
        }
        execution = executions.findById(id).orElseThrow();
        require(execution.getStatus().isTerminal(), "TERMINAL_STATUS_NOT_OBSERVED");
        boolean noPayout = !(amount.equals("666.00") || amount.equals("777.00")) || !side.equals("SOURCE") || execution.getExternalPayoutId() == null;
        check(boundaryId() + "-terminal", execution.getStatus() == expected && noPayout,
                "Terminal " + expected + (side.equals("SOURCE") && !expected.equals(TransferExecutionStatus.COMPLETED) ? " without payout" : ""),
                execution.getStatus().name(), before, Map.of("observed_states", observed, "requote_accepted", confirmedRequote,
                        "payout_created", execution.getExternalPayoutId() != null, "requote_attempts", execution.getPayoutQuoteAttempts()));
        verifyPersistence(execution);
        if (forceExpiry) {
            check(boundaryId() + "-expired-during-funding", execution.getPayoutQuoteAttempts() > 0,
                    "New payout quote after local expiry", "Attempts=" + execution.getPayoutQuoteAttempts(), before, Map.of("expiry_changed_locally", true));
        }
        int terminalBefore = boundary.count();
        String original = persistedSnapshot(id);
        processor.reconcile(id);
        var after = local("POST", confirmPath(quote), confirmation(quote), tokenA);
        check(boundaryId() + "-terminal-immutable", after.statusCode() == 200 && boundary.count() == terminalBefore && original.equals(persistedSnapshot(id)),
                "No terminal mutation or new provider call", "HTTP " + after.statusCode(), terminalBefore, Map.of());
    }

    private boolean uncertain(TransferExecutionEntity execution) {
        return Set.of("PAYOUT_UNCERTAIN", "PAYIN_UNCERTAIN", "PROVIDER_RESULT_UNKNOWN").contains(
                execution.getFailureCode() == null ? "" : execution.getFailureCode());
    }

    private void verifyPersistence(TransferExecutionEntity execution) {
        boolean links = company.getId().equals(execution.getCompanyId()) && beneficiary.getId().equals(execution.getBeneficiaryId());
        var rows = jdbc.queryForList("SELECT company_id, foreign_amount, foreign_currency, settlement_amount_brl, service_fee_brl, status::text AS status FROM base_transactions WHERE id=?", execution.getTransactionId());
        boolean amounts = rows.size() == 1 && company.getId().equals(rows.getFirst().get("company_id"))
                && ((BigDecimal) rows.getFirst().get("foreign_amount")).compareTo(execution.getTargetAmount()) == 0
                && "USD".equals(rows.getFirst().get("foreign_currency"));
        check(boundaryId() + "-persistence", links && amounts, "Ownership and amounts agree across execution/history",
                "History rows=" + rows.size(), boundary.count(), Map.of("source_currency", execution.getSourceCurrency(), "target_currency", execution.getTargetCurrency()));
        if (rows.size() == 1 && execution.getTotalFee() != null && execution.getTotalFee().signum() > 0) {
            BigDecimal storedFee = (BigDecimal) rows.getFirst().get("service_fee_brl");
            boolean relabeled = storedFee != null && storedFee.compareTo(execution.getTotalFee()) == 0;
            check(boundaryId() + "-fee-persistence", !relabeled,
                    "Token fee must be converted before storing in service_fee_brl",
                    "Compared token-denominated quote fee to BRL history column", boundary.count(),
                    Map.of("quote_fee_unit", "USDB", "token_fee", execution.getTotalFee().toPlainString(),
                            "history_fee_unit", "BRL", "history_fee", storedFee == null ? "null" : storedFee.toPlainString()));
        }
    }

    private void expiredOffer() throws Exception {
        QuoteResponse quote = quote("SOURCE", "100.00");
        ProviderQuoteEntity offer = offers.findById(quote.offer().offerId()).orElseThrow();
        offer.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)); offers.saveAndFlush(offer);
        int before = boundary.count();
        var response = local("POST", confirmPath(quote), confirmation(quote), tokenA);
        check("TC-SBX-BP-04-expired-rejected", response.statusCode() == 422 && before == boundary.count(),
                "Expired offer rejected before provider call", "HTTP " + response.statusCode(), before, Map.of("expiry_changed_locally", true));
    }

    private QuoteResponse quote(String side, String amount) throws Exception {
        var response = local("POST", "/v1/quotes", quoteBody(side, amount), tokenA);
        require(response.statusCode() == 200, "QUOTE_HTTP_" + response.statusCode());
        return json.readValue(response.body(), QuoteResponse.class);
    }

    private void verifyQuote(QuoteResponse quote, String side, String amount) throws Exception {
        ProviderQuoteEntity saved = offers.findById(quote.offer().offerId()).orElseThrow();
        JsonNode metadata = json.readTree(saved.getProviderMetadata());
        JsonNode funding = boundary.quoteResponse(metadata.get("fundingQuoteId").asString());
        JsonNode payout = boundary.quoteResponse(metadata.get("payoutQuoteId").asString());
        JsonNode fundingRequest = boundary.quoteRequest(metadata.get("fundingQuoteId").asString());
        JsonNode payoutRequest = boundary.quoteRequest(metadata.get("payoutQuoteId").asString());
        BigDecimal fixed = side.equals("SOURCE") ? saved.getSourceAmount() : saved.getTargetAmount();
        boolean persisted = fixed.compareTo(new BigDecimal(amount)) == 0
                && saved.getSourceAmount().compareTo(funding.get("sender_amount").decimalValue().movePointLeft(2)) == 0
                && saved.getTargetAmount().compareTo(payout.get("receiver_amount").decimalValue().movePointLeft(2)) == 0
                && saved.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        boolean cents = fundingRequest.get("request_amount").decimalValue().scale() <= 0
                && payoutRequest.get("request_amount").decimalValue().scale() <= 0
                && fundingRequest.get("cover_fees").asBoolean() == side.equals("TARGET")
                && payoutRequest.get("cover_fees").asBoolean() == side.equals("TARGET");
        check(boundaryId() + "-quote-contract", persisted && cents,
                "Fixed amount, cents, cover_fees, expiry and stored quote agree", "Compared real request/response and database",
                boundary.count(), Map.of("amount_side", side, "fixed_amount", amount, "source_currency", "BRL", "target_currency", "USD", "token", "USDB", "network", "base_sepolia"));
        boolean nonnegative = true;
        for (JsonNode node : List.of(funding, payout)) {
            for (String field : List.of("flat_fee", "billing_fee_amount", "partner_fee_amount")) {
                if (node.has(field) && node.get(field).isNumber() && node.get(field).decimalValue().signum() < 0) { nonnegative = false; }
            }
        }
        check(boundaryId() + "-observed-fees", nonnegative, "Observed fee components nonnegative",
                "Checked components separately; no cross-currency sum used as assertion", boundary.count(), Map.of("negative_fee_defense_proven", false));
        BigDecimal fundingFee = fee(funding);
        BigDecimal payoutFee = fee(payout);
        // Current OpenAPI documents flat_fee/partner_fee_amount in token cents on BOTH sides.
        // Do not infer fee currency from sender_amount currency (BRL for payin).
        boolean tokenFeeSum = saved.getTotalFee().compareTo(fundingFee.add(payoutFee).movePointLeft(2)) == 0;
        check(boundaryId() + "-fee-currency", tokenFeeSum,
                "Sum token-denominated fee components without mixing sender currency", "Compared to token-cent contract in current OpenAPI",
                boundary.count(), Map.of("funding_fee_currency", "USDB", "payout_fee_currency", "USDB",
                        "billing_fee_interpretation", "Separate invoice component; observed independently"));
    }

    private BigDecimal fee(JsonNode node) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String field : List.of("flat_fee", "billing_fee_amount", "partner_fee_amount")) {
            if (node.has(field) && node.get(field).isNumber()) { sum = sum.add(node.get(field).decimalValue()); }
        }
        return sum;
    }

    private String persistedSnapshot(UUID id) {
        return jdbc.queryForMap("SELECT * FROM transfer_executions WHERE id=?", id).toString(); // memory-only equality
    }
    private String quoteBody(String side, String amount) throws Exception {
        return json.writeValueAsString(Map.ofEntries(Map.entry("beneficiaryId", beneficiary.getId()), Map.entry("direction", "PAYOUT"),
                Map.entry("sourceCurrency", "BRL"), Map.entry("targetCurrency", "USD"), Map.entry("sourcePaymentMethod", "pix"),
                Map.entry("targetPaymentMethod", "wire"), Map.entry("amount", new BigDecimal(amount)), Map.entry("amountSide", side),
                Map.entry("token", "USDB"), Map.entry("blockchainNetwork", "base_sepolia"), Map.entry("coverFees", false), Map.entry("description", "SEC85 Sandbox")));
    }
    private String confirmation(QuoteResponse quote) throws Exception {
        return json.writeValueAsString(Map.of("sourceAmount", quote.offer().sourceAmount(), "targetAmount", quote.offer().targetAmount()));
    }
    private String confirmPath(QuoteResponse quote) { return "/v1/quotes/offers/" + quote.offer().offerId() + "/confirm"; }
    private HttpResponse<String> local(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(100))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        return localHttp.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String currentScenario;
    private String boundaryId() { return currentScenario; }
    @FunctionalInterface private interface Scenario { void run() throws Exception; }
    private void scenario(String id, Scenario action) {
        currentScenario = id;
        boundary.scenario(id);
        int before = boundary.count();
        if (boundary.halted() || failure != null) {
            record(id, "BLOQUEADO", "Scenario completed", "Stopped: " + (failure == null ? boundary.stopReason() : failure), 0, Map.of()); return;
        }
        try { action.run(); }
        catch (Exception ex) {
            failure = ex.getClass().getSimpleName();
            // SafeAbort has fixed code only; never use provider exception messages.
            String code = ex instanceof SafeAbort ? ex.getMessage() : ex.getClass().getSimpleName();
            record(id, "BLOQUEADO", "Scenario completed", code, boundary.count() - before, Map.of());
        }
    }
    private void check(String id, boolean condition, String expected, String observed, int before, Map<String, Object> details) {
        record(id, condition ? "PASS" : "FAIL", expected, observed, boundary.count() - before, details);
    }
    private void record(String id, String status, String expected, String observed, int calls, Map<String, Object> details) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id); item.put("status", status); item.put("expected", expected); item.put("observed", observed);
        item.put("external_calls_during_check", calls); item.put("details", details); results.add(item);
        System.out.println("SEC85_SANDBOX " + id + " " + status + " calls=" + boundary.count());
        try { export(); }
        catch (Exception ex) { throw new IllegalStateException("Could not persist sanitized checkpoint"); }
    }
    private void require(boolean condition, String code) throws SafeAbort { if (!condition) { throw new SafeAbort(code); } }
    private static class SafeAbort extends Exception { SafeAbort(String code) { super(code); } }

    private void export() throws Exception {
        String directory = System.getenv("BLINDPAY_SECURITY_EVIDENCE_DIR");
        if (directory == null) { throw new IllegalStateException("BLINDPAY_SECURITY_EVIDENCE_DIR required"); }
        Path output = Path.of(directory).toAbsolutePath().normalize();
        require(output.startsWith(Path.of("docs/security/evidencias-85-sandbox").toAbsolutePath().normalize()), "EVIDENCE_PATH_OUTSIDE_SCOPE");
        Files.createDirectories(output);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("issue", "AGES-V-Stable/cicd#85");
        evidence.put("commit", System.getenv("BLINDPAY_SECURITY_COMMIT"));
        evidence.put("branch", "test/85-integracoes-sandbox");
        evidence.put("started_utc", started.toString()); evidence.put("checkpoint_utc", Instant.now().toString());
        evidence.put("execution_state", finished ? "FINISHED" : "RUNNING");
        if (finished) { evidence.put("finished_utc", Instant.now().toString()); }
        evidence.put("environment", "BlindPay Development; local loopback HTTP; ephemeral PostgreSQL 16.4");
        evidence.put("budget", Map.of("max_calls", BlindPaySandboxBoundary.MAX_CALLS, "max_minutes", 25, "min_interval_ms", 3000));
        evidence.put("external_calls", boundary.currentCalls()); evidence.put("stop_reason", boundary.stopReason());
        evidence.put("budget_consumed", boundary.count());
        evidence.put("preceding_budget_consumed", boundary.priorCalls());
        evidence.put("cases", results); evidence.put("calls", boundary.calls()); evidence.put("resources", resources);
        Files.writeString(output.resolve("assessment.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
