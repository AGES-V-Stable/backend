package ages.vstable.backend.controller;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TotpTestCodes;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.utils.JwtTokenUtils;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa (JWT real, filtro que recarrega o vínculo, controller,
 * {@code TwoFactorAuthService} real, transação e bloqueio de linha), a exigência do TOTP nas confirmações
 * do orquestrador: rejeição sem persistência nem chamada a provedor, consumo só quando o estado muda,
 * replay, idempotência e concorrência. BlindPay e Avenia são mocks; nenhum serviço externo é chamado.
 * Ignorado quando não há Docker.
 */
@SpringBootTest(properties = "transfers.reconciliation.enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TransferExecutionTotpIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 30;
    private static final String SOURCE = "1000.00";
    private static final String TARGET = "990.00";

    @MockitoBean
    private BlindPayGateway blindPayGateway;

    @MockitoBean
    private BlindPayProvisioningService provisioningService;

    @MockitoBean
    private AveniaTransferService aveniaTransferService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private TransferExecutionRepository executionRepository;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID beneficiaryId;
    private UserEntity operator;
    private String operatorSecret;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        when(blindPayGateway.createEvmPayin(anyString(), anyString())).thenAnswer(call -> payin());
        companyId = insertCompany();
        beneficiaryId = insertBeneficiary(companyId);
        operatorSecret = TotpTestCodes.newSecret();
        operator = insertUser(companyId, operatorSecret);
        insertMembership(operator, companyId, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        operatorToken = jwtTokenUtils.generateToken(operator);
    }

    @Test
    void confirm_validTotp_registersOnce_andTheSameCodeCannotConfirmAnotherOffer() throws Exception {
        // Arrange
        UUID firstOffer = insertSelectedOffer(operator);
        UUID secondOffer = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act
        mockMvc.perform(confirm(firstOffer, operatorToken, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_FUNDS"))
                .andExpect(jsonPath("$.fundingInstructions").value("pix-code"));
        mockMvc.perform(confirm(secondOffer, operatorToken, code))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("TOTP code has already been used"));

        // Assert
        assertThat(executionsFor(firstOffer)).isEqualTo(1);
        assertNotExecuted(secondOffer);
        verify(blindPayGateway, times(1)).createEvmPayin(anyString(), anyString());
        verifyNoInteractions(aveniaTransferService);
    }

    @Test
    void confirm_incorrectTotp_returns401WithoutPersistingOrCallingProviders() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);

        // Act
        mockMvc.perform(confirm(offerId, operatorToken, wrongCode(operatorSecret)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // Assert
        assertNotExecuted(offerId);
        verifyNoInteractions(blindPayGateway, aveniaTransferService);
    }

    @Test
    void confirm_missingOrMalformedTotp_returns400WithoutPersisting() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);

        // Act
        mockMvc.perform(authorized(post("/v1/quotes/offers/{offerId}/confirm", offerId), operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAmount\": " + SOURCE + ", \"targetAmount\": " + TARGET + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(confirm(offerId, operatorToken, "12345"))
                .andExpect(status().isBadRequest());

        // Assert
        assertNotExecuted(offerId);
        verifyNoInteractions(blindPayGateway, aveniaTransferService);
    }

    @Test
    void confirm_accountWithoutTwoFactor_returns422WithoutPersisting() throws Exception {
        // Arrange
        UserEntity withoutTwoFactor = insertUser(companyId, null);
        insertMembership(withoutTwoFactor, companyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        UUID offerId = insertSelectedOffer(withoutTwoFactor);

        // Act
        mockMvc.perform(confirm(offerId, jwtTokenUtils.generateToken(withoutTwoFactor), "123456"))
                .andExpect(status().isUnprocessableContent());

        // Assert
        assertNotExecuted(offerId);
        verifyNoInteractions(blindPayGateway, aveniaTransferService);
    }

    @Test
    void confirm_rejectedOperation_doesNotConsumeTheCode() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act: valores diferentes da oferta são recusados; o mesmo código continua válido.
        mockMvc.perform(confirm(offerId, operatorToken, code, SOURCE, "995.00"))
                .andExpect(status().isUnprocessableContent());
        assertNotExecuted(offerId);
        mockMvc.perform(confirm(offerId, operatorToken, code))
                .andExpect(status().isOk());

        // Assert
        assertThat(executionsFor(offerId)).isEqualTo(1);
    }

    @Test
    void confirm_repeatedBySameUser_returnsTheSameExecution_andOtherPrincipalsCannotReuseIt() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);
        String executionId = executionIdOf(mockMvc.perform(confirm(offerId, operatorToken, code))
                .andExpect(status().isOk()).andReturn());

        // Act / Assert: reenvio com o mesmo código (já consumido) devolve a mesma execução.
        mockMvc.perform(confirm(offerId, operatorToken, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId));

        // Outro membro com permissão, mesmo com TOTP válido, não recebe a execução como se a tivesse confirmado.
        String colleagueSecret = TotpTestCodes.newSecret();
        UserEntity colleague = insertUser(companyId, colleagueSecret);
        insertMembership(colleague, companyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        mockMvc.perform(confirm(offerId, jwtTokenUtils.generateToken(colleague),
                        TotpTestCodes.code(colleagueSecret, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_ALREADY_CONFIRMED"));

        // Perfil sem permissão e membro removido param antes do serviço.
        mockMvc.perform(confirm(offerId, tokenOf(companyId, CompanyRole.VIEWER, MembershipStatus.ACTIVE), "123456"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(confirm(offerId, tokenOf(companyId, CompanyRole.OPERATOR, MembershipStatus.REMOVED), "123456"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        assertThat(executionsFor(offerId)).isEqualTo(1);
        verify(blindPayGateway, times(1)).createEvmPayin(anyString(), anyString());
    }

    @Test
    void confirm_memberOfAnotherCompany_returns404WithoutPersistingOrConsumingTheCode() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);
        UUID otherCompany = insertCompany();
        String outsiderSecret = TotpTestCodes.newSecret();
        UserEntity outsider = insertUser(otherCompany, outsiderSecret);
        insertMembership(outsider, otherCompany, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String outsiderToken = jwtTokenUtils.generateToken(outsider);
        String code = TotpTestCodes.code(outsiderSecret, 0);

        // Act
        mockMvc.perform(confirm(offerId, outsiderToken, code))
                .andExpect(status().isNotFound());

        // Assert: a oferta continua intacta e o código do usuário não foi gasto.
        assertNotExecuted(offerId);
        UUID ownOffer = insertSelectedOffer(outsider, otherCompany, insertBeneficiary(otherCompany));
        mockMvc.perform(confirm(ownOffer, outsiderToken, code))
                .andExpect(status().isOk());
    }

    @Test
    void confirm_concurrentRequestsForTheSameOffer_createOneExecutionAndBothReturnIt() throws Exception {
        // Arrange
        UUID offerId = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(confirm(offerId, operatorToken, code)).andReturn(),
                () -> mockMvc.perform(confirm(offerId, operatorToken, code)).andReturn());

        // Assert
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(executionIdOf(results.get(0))).isEqualTo(executionIdOf(results.get(1)));
        assertThat(executionsFor(offerId)).isEqualTo(1);
        verify(blindPayGateway, times(1)).createEvmPayin(anyString(), anyString());
    }

    @Test
    void confirm_concurrentRequestsWithTheSameCodeForDifferentOffers_acceptOnlyOne() throws Exception {
        // Arrange
        UUID firstOffer = insertSelectedOffer(operator);
        UUID secondOffer = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(confirm(firstOffer, operatorToken, code)).andReturn(),
                () -> mockMvc.perform(confirm(secondOffer, operatorToken, code)).andReturn());

        // Assert
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 401);
        assertThat(executionsFor(firstOffer) + executionsFor(secondOffer)).isEqualTo(1);
        verify(blindPayGateway, times(1)).createEvmPayin(anyString(), anyString());
    }

    @Test
    void confirm_providerTimeoutAfterRegistration_keepsExecutionForReconciliationAndTheCodeConsumed() throws Exception {
        // Arrange
        when(blindPayGateway.createEvmPayin(anyString(), anyString()))
                .thenThrow(BlindPayIntegrationException.communication("timeout", null));
        UUID offerId = insertSelectedOffer(operator);
        UUID anotherOffer = insertSelectedOffer(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act
        mockMvc.perform(confirm(offerId, operatorToken, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SUBMISSION"));

        // Assert
        assertThat(executionsFor(offerId)).isEqualTo(1);
        mockMvc.perform(confirm(anotherOffer, operatorToken, code))
                .andExpect(status().isUnauthorized());
        assertNotExecuted(anotherOffer);
    }

    @Test
    void confirmRequote_requiresValidUnusedTotp_beforeChangingStateOrCallingTheProvider() throws Exception {
        // Arrange
        when(provisioningService.ensureManagedWallet(any(), anyString()))
                .thenReturn(new BlindPayProvisioningService.ManagedWallet("bl_000000000001", "0xmanaged"));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenAnswer(call -> payout());
        UUID executionId = insertRequoteRequired(operator);
        UUID anotherExecution = insertRequoteRequired(operator);
        String code = TotpTestCodes.code(operatorSecret, 0);

        // Act / Assert: código incorreto não altera nada.
        mockMvc.perform(confirmRequote(executionId, operatorToken, wrongCode(operatorSecret)))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(executionId)).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());

        // Código válido aceita a nova cotação e envia o payout.
        mockMvc.perform(confirmRequote(executionId, operatorToken, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYOUT_PROCESSING"));

        // O mesmo código não aceita outra recotação.
        mockMvc.perform(confirmRequote(anotherExecution, operatorToken, code))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(anotherExecution)).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        verify(blindPayGateway, times(1)).createEvmPayout(any(), anyString());
    }

    @Test
    void confirmRequote_concurrentConfirmations_changeTheStateOnlyOnce() throws Exception {
        // Arrange: dois códigos válidos distintos, para que só a trava de estado decida.
        when(provisioningService.ensureManagedWallet(any(), anyString()))
                .thenReturn(new BlindPayProvisioningService.ManagedWallet("bl_000000000001", "0xmanaged"));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenAnswer(call -> payout());
        UUID executionId = insertRequoteRequired(operator);
        String firstCode = TotpTestCodes.code(operatorSecret, 0);
        String secondCode = TotpTestCodes.freshCode(operatorSecret, firstCode);

        // Act
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(confirmRequote(executionId, operatorToken, firstCode)).andReturn(),
                () -> mockMvc.perform(confirmRequote(executionId, operatorToken, secondCode)).andReturn());

        // Assert
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        verify(blindPayGateway, times(1)).createEvmPayout(any(), anyString());
    }

    @Test
    void confirmRequote_memberOfAnotherCompany_returns404WithoutChangingState() throws Exception {
        // Arrange
        UUID executionId = insertRequoteRequired(operator);
        UUID otherCompany = insertCompany();
        String outsiderSecret = TotpTestCodes.newSecret();
        UserEntity outsider = insertUser(otherCompany, outsiderSecret);
        insertMembership(outsider, otherCompany, CompanyRole.OWNER, MembershipStatus.ACTIVE);

        // Act
        mockMvc.perform(confirmRequote(executionId, jwtTokenUtils.generateToken(outsider),
                        TotpTestCodes.code(outsiderSecret, 0)))
                .andExpect(status().isNotFound());

        // Assert
        assertThat(statusOf(executionId)).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    private void assertNotExecuted(UUID offerId) {
        assertThat(executionsFor(offerId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM provider_quotes WHERE id = ?", String.class, offerId))
                .isEqualTo("SELECTED");
        // Nenhuma transação financeira órfã: cada uma pertence a uma execução registrada.
        UUID company = companyOf(offerId);
        Integer transactions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM base_transactions WHERE company_id = ?", Integer.class, company);
        Integer registered = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer_executions WHERE company_id = ? AND transaction_id IS NOT NULL",
                Integer.class, company);
        assertThat(transactions).isEqualTo(registered);
    }

    private UUID companyOf(UUID offerId) {
        return jdbcTemplate.queryForObject("""
                SELECT r.company_id FROM provider_quotes q JOIN quote_requests r ON r.id = q.quote_request_id
                WHERE q.id = ?
                """, UUID.class, offerId);
    }

    private int executionsFor(UUID offerId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer_executions WHERE provider_quote_id = ?", Integer.class, offerId);
    }

    private TransferExecutionStatus statusOf(UUID executionId) {
        return executionRepository.findById(executionId).orElseThrow().getStatus();
    }

    private static String executionIdOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"executionId\":\"") + "\"executionId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    @SafeVarargs
    private List<MvcResult> concurrently(Callable<MvcResult>... requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> request : requests) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder confirm(UUID offerId, String token, String totpCode) {
        return confirm(offerId, token, totpCode, SOURCE, TARGET);
    }

    private MockHttpServletRequestBuilder confirm(
            UUID offerId, String token, String totpCode, String source, String target) {
        return authorized(post("/v1/quotes/offers/{offerId}/confirm", offerId), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(source, target, totpCode));
    }

    private MockHttpServletRequestBuilder confirmRequote(UUID executionId, String token, String totpCode) {
        return authorized(post("/v1/transfer-executions/{executionId}/requote/confirm", executionId), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(SOURCE, "980.00", totpCode));
    }

    private static String body(String source, String target, String totpCode) {
        return """
                {"sourceAmount": %s, "targetAmount": %s, "totpCode": "%s"}
                """.formatted(source, target, totpCode);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** Código bem formado que não é aceito em nenhuma janela tolerada. */
    private static String wrongCode(String secret) {
        List<String> valid = List.of(TotpTestCodes.code(secret, -1), TotpTestCodes.code(secret, 0),
                TotpTestCodes.code(secret, 1), TotpTestCodes.code(secret, 2));
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
    }

    private String tokenOf(UUID company, CompanyRole role, MembershipStatus status) {
        UserEntity user = insertUser(company, TotpTestCodes.newSecret());
        insertMembership(user, company, role, status);
        return jwtTokenUtils.generateToken(user);
    }

    private UUID insertCompany() {
        UUID id = UUID.randomUUID();
        String cnpj = "%014d".formatted(ThreadLocalRandom.current().nextLong(1L, 99_999_999_999_999L));
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UUID insertBeneficiary(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO beneficiaries (id, company_id, nickname, account_holder_name, currency, payment_rail)
                VALUES (?, ?, 'Fornecedor', 'Fornecedor LLC', 'USD', 'wire')
                """, id, company);
        return id;
    }

    private UserEntity insertUser(UUID company, String twoFactorSecret) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return userRepository.saveAndFlush(UserEntity.builder()
                .companyId(company)
                .fullName("Usuario " + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com")
                .passwordHash("h")
                .passwordSalt("s")
                .twoFactorSecret(twoFactorSecret)
                .twoFactorEnabled(twoFactorSecret != null)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private CompanyMembershipEntity insertMembership(UserEntity user, UUID company, CompanyRole role,
                                                     MembershipStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return membershipRepository.saveAndFlush(CompanyMembershipEntity.builder()
                .companyId(company)
                .userId(user.getId())
                .role(role)
                .status(status)
                .joinedAt(now)
                .removedAt(status == MembershipStatus.REMOVED ? now : null)
                .updatedAt(now)
                .build());
    }

    private UUID insertSelectedOffer(UserEntity requester) {
        return insertSelectedOffer(requester, companyId, beneficiaryId);
    }

    /** Oferta BlindPay vencedora (origem BRL), válida por 4 minutos. */
    private UUID insertSelectedOffer(UserEntity requester, UUID company, UUID beneficiary) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO quote_requests (id, company_id, requested_by, beneficiary_id, direction, source_currency,
                    target_currency, source_payment_method, target_payment_method, requested_amount, amount_side,
                    token, blockchain_network, description, status)
                VALUES (?, ?, ?, ?, 'PAYOUT', 'BRL', 'USD', 'pix', 'wire', 1000.00, 'SOURCE', 'USDB',
                    'base_sepolia', 'Invoice 42', 'COMPLETED')
                """, requestId, company, requester.getId(), beneficiary);
        UUID offerId = UUID.randomUUID();
        long payoutExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(4).toInstant().toEpochMilli();
        jdbcTemplate.update("""
                INSERT INTO provider_quotes (id, quote_request_id, provider, external_quote_id, idempotency_key, status,
                    source_amount, target_amount, exchange_rate, total_fee, expires_at, provider_metadata)
                VALUES (?, ?, 'BLINDPAY', 'qu_000000000001', ?, 'SELECTED', 1000.00, 990.00, 0.99, 2.50, ?,
                    CAST(? AS jsonb))
                """, offerId, requestId, "test-" + offerId, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(4),
                """
                {"fundingQuoteId":"pq_000000000001","payoutQuoteId":"qu_000000000001",
                 "payoutSenderAmount":"180.12","payoutReceiverAmount":"990.00","payoutExpiresAt":%d}
                """.formatted(payoutExpiresAt));
        return offerId;
    }

    /** Execução financiada aguardando o aceite da nova cotação de payout (990 -> 980). */
    private UUID insertRequoteRequired(UserEntity requester) {
        UUID offerId = insertSelectedOffer(requester);
        jdbcTemplate.update("UPDATE provider_quotes SET status = 'CONFIRMED' WHERE id = ?", offerId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return executionRepository.saveAndFlush(TransferExecutionEntity.builder()
                .providerQuoteId(offerId)
                .companyId(companyId)
                .requestedBy(requester.getId())
                .beneficiaryId(beneficiaryId)
                .provider(IntegrationProvider.BLINDPAY)
                .status(TransferExecutionStatus.REQUOTE_REQUIRED)
                .sourceCurrency("BRL")
                .targetCurrency("USD")
                .sourceAmount(new BigDecimal(SOURCE))
                .targetAmount(new BigDecimal(TARGET))
                .fundedAmount(new BigDecimal("180.12"))
                .externalPayinQuoteId("pq_000000000001")
                .externalPayinId("pi_" + UUID.randomUUID())
                .externalPayoutQuoteId("qu_000000000002")
                .payoutQuoteSenderAmount(new BigDecimal("180.12"))
                .payoutQuoteReceiverAmount(new BigDecimal("980.00"))
                .payoutQuoteExpiresAt(now.plusMinutes(4))
                .payoutQuoteAttempts(1)
                .createdAt(now)
                .updatedAt(now)
                .build()).getId();
    }

    private static BlindPayPayinResponse payin() {
        return new BlindPayPayinResponse("pi_" + UUID.randomUUID(), "pending", null, "pix", "pix-code",
                null, null, null, "BRL", "USDB", null, null, null, null, null, null, null, null, null);
    }

    private static BlindPayPayoutResponse payout() {
        return new BlindPayPayoutResponse("po_" + UUID.randomUUID(), "processing", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
