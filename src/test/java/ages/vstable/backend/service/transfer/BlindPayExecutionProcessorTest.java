package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderQuoteEntity;
import ages.vstable.backend.entity.QuoteRequestEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayEvmPayoutRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlindPayExecutionProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private TransferExecutionRepository executionRepository;
    @Mock private ProviderQuoteRepository providerQuoteRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private BeneficiaryRepository beneficiaryRepository;
    @Mock private BaseTransactionRepository baseTransactionRepository;
    @Mock private BlindPayGateway blindPayGateway;
    @Mock private BlindPayProvisioningService provisioningService;

    private final Map<UUID, TransferExecutionEntity> executions = new HashMap<>();
    private BlindPayExecutionProcessor processor;
    private CompanyEntity company;
    private BeneficiaryEntity beneficiary;
    private ProviderQuoteEntity offer;
    private BaseTransactionEntity transaction;

    @BeforeEach
    void setUp() {
        processor = new BlindPayExecutionProcessor(executionRepository, providerQuoteRepository, companyRepository,
                beneficiaryRepository, baseTransactionRepository, blindPayGateway, provisioningService,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        QuoteRequestEntity request = QuoteRequestEntity.builder()
                .id(UUID.randomUUID()).company(company).beneficiary(beneficiary)
                .token("USDB").blockchainNetwork("base_sepolia").description("Invoice 42").build();
        offer = ProviderQuoteEntity.builder().id(UUID.randomUUID()).quoteRequest(request)
                .provider(IntegrationProvider.BLINDPAY).build();
        transaction = BaseTransactionEntity.builder().id(UUID.randomUUID())
                .status(TransactionStatus.AWAITING_PAYMENT).foreignAmount(new BigDecimal("990.00")).build();

        when(executionRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(executions.get(call.getArgument(0))));
        when(executionRepository.findForUpdate(any())).thenAnswer(call -> Optional.ofNullable(executions.get(call.getArgument(0))));
        when(executionRepository.findByIdAndCompanyId(any(), any())).thenAnswer(call ->
                Optional.ofNullable(executions.get(call.getArgument(0)))
                        .filter(execution -> execution.getCompanyId().equals(call.getArgument(1))));
        when(executionRepository.findByExternalPayinId(anyString())).thenAnswer(call -> executions.values().stream()
                .filter(execution -> call.getArgument(0).equals(execution.getExternalPayinId())).findFirst());
        when(executionRepository.findByExternalPayoutId(anyString())).thenAnswer(call -> executions.values().stream()
                .filter(execution -> call.getArgument(0).equals(execution.getExternalPayoutId())).findFirst());
        when(executionRepository.save(any())).thenAnswer(call -> {
            TransferExecutionEntity execution = call.getArgument(0);
            executions.put(execution.getId(), execution);
            return execution;
        });
        when(providerQuoteRepository.findById(offer.getId())).thenReturn(Optional.of(offer));
        when(companyRepository.findById(company.getId())).thenReturn(Optional.of(company));
        when(beneficiaryRepository.findById(beneficiary.getId())).thenReturn(Optional.of(beneficiary));
        when(baseTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(provisioningService.ensureManagedWallet(company, "base_sepolia"))
                .thenReturn(new BlindPayProvisioningService.ManagedWallet("bl_000000000001", "0xmanaged"));
        when(provisioningService.ensureBankAccount(company, beneficiary)).thenReturn("ba_000000000001");
    }

    @Test
    void start_brlExecution_createsPayinWithStableKeyAndWaitsForFunds() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PENDING_SUBMISSION);
        when(blindPayGateway.createEvmPayin("pq_000000000001", key(execution, "payin")))
                .thenReturn(payin("pi_000000000001", "processing", null));

        // Act
        processor.start(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(execution.getExternalPayinId()).isEqualTo("pi_000000000001");
        assertThat(execution.getFundingInstructions()).isEqualTo("pix-copy-paste");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void start_payinWithoutResponse_isRetriedWithSameIdempotencyKeyByReconciliation() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PENDING_SUBMISSION);
        when(blindPayGateway.createEvmPayin("pq_000000000001", key(execution, "payin")))
                .thenThrow(BlindPayIntegrationException.communication("timeout", null))
                .thenReturn(payin("pi_000000000001", "processing", null));

        // Act
        processor.start(execution.getId());
        TransferExecutionStatus afterTimeout = execution.getStatus();
        processor.reconcile(execution.getId());

        // Assert
        assertThat(afterTimeout).isEqualTo(TransferExecutionStatus.PENDING_SUBMISSION);
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        verify(blindPayGateway, times(2)).createEvmPayin("pq_000000000001", key(execution, "payin"));
    }

    @Test
    void start_payinRejectedByProvider_failsWithoutRetrying() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PENDING_SUBMISSION);
        when(blindPayGateway.createEvmPayin(anyString(), anyString())).thenThrow(rejected("QUOTE_EXPIRED"));

        processor.start(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void payinStillProcessing_doesNotSendPayout() {
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001")).thenReturn(payin("pi_000000000001", "processing", null));

        boolean handled = processor.onPayinEvent("pi_000000000001");

        assertThat(handled).isTrue();
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void payinCompleted_withValidQuote_sendsPayoutFromManagedWallet() {
        // Arrange
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createEvmPayout(any(), anyString()))
                .thenReturn(payout("po_000000000001", "processing"));

        // Act
        processor.onPayinEvent("pi_000000000001");

        // Assert
        ArgumentCaptor<BlindPayEvmPayoutRequest> request = ArgumentCaptor.forClass(BlindPayEvmPayoutRequest.class);
        verify(blindPayGateway).createEvmPayout(request.capture(), eq(key(execution, "payout:0")));
        assertThat(request.getValue().quoteId()).isEqualTo("qu_000000000001");
        assertThat(request.getValue().senderWalletAddress()).isEqualTo("0xmanaged");
        assertThat(request.getValue().walletId()).isEqualTo("bl_000000000001");
        assertThat(execution.getFundedAmount()).isEqualByComparingTo("180.12");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        assertThat(execution.getExternalPayoutId()).isEqualTo("po_000000000001");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void payinCompleted_expiredQuote_requotesFundedAmountAndPaysWhenNotWorse() {
        // Arrange
        TransferExecutionEntity execution = awaitingFunds();
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createQuote(any(), eq(key(execution, "payout-quote:1"))))
                .thenReturn(payoutQuote("qu_000000000002", "18012", "99500"));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenReturn(payout("po_000000000001", "processing"));

        // Act
        processor.onPayinEvent("pi_000000000001");

        // Assert
        ArgumentCaptor<BlindPayQuoteRequest> quote = ArgumentCaptor.forClass(BlindPayQuoteRequest.class);
        verify(blindPayGateway).createQuote(quote.capture(), anyString());
        assertThat(quote.getValue().currencyType()).isEqualTo(BlindPayApi.Quote.CurrencyType.sender);
        assertThat(quote.getValue().requestAmount()).isEqualTo(18012L);
        assertThat(quote.getValue().coverFees()).isFalse();
        assertThat(quote.getValue().bankAccountId()).isEqualTo("ba_000000000001");
        ArgumentCaptor<BlindPayEvmPayoutRequest> payout = ArgumentCaptor.forClass(BlindPayEvmPayoutRequest.class);
        verify(blindPayGateway).createEvmPayout(payout.capture(), eq(key(execution, "payout:1")));
        assertThat(payout.getValue().quoteId()).isEqualTo("qu_000000000002");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
    }

    @Test
    void payinCompleted_requoteDeliversLess_requiresNewConfirmationAndDoesNotPay() {
        // Arrange
        TransferExecutionEntity execution = awaitingFunds();
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createQuote(any(), anyString()))
                .thenReturn(payoutQuote("qu_000000000002", "18012", "98000"));

        // Act
        processor.onPayinEvent("pi_000000000001");

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        assertThat(execution.getPayoutQuoteReceiverAmount()).isEqualByComparingTo("980.00");
        assertThat(execution.getTargetAmount()).isEqualByComparingTo("990.00");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.HELD);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void acceptRequote_matchingNewAmounts_sendsPayoutForConfirmedValue() {
        // Arrange
        TransferExecutionEntity execution = requoteRequired();
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenReturn(payout("po_000000000001", "processing"));

        // Act
        processor.acceptRequote(execution.getId(), company.getId(), new BigDecimal("1000.00"), new BigDecimal("980.00"));

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        assertThat(execution.getTargetAmount()).isEqualByComparingTo("980.00");
        assertThat(transaction.getForeignAmount()).isEqualByComparingTo("980.00");
        verify(blindPayGateway).createEvmPayout(any(), eq(key(execution, "payout:1")));
    }

    @Test
    void acceptRequote_differentAmounts_isRejectedWithoutPaying() {
        TransferExecutionEntity execution = requoteRequired();

        assertThatThrownBy(() -> processor.acceptRequote(
                execution.getId(), company.getId(), new BigDecimal("1000.00"), new BigDecimal("990.00")))
                .isInstanceOf(UnprocessableEntityException.class);

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void acceptRequote_withoutPendingRequote_isConflict() {
        TransferExecutionEntity execution = awaitingFunds();

        assertThatThrownBy(() -> processor.acceptRequote(
                execution.getId(), company.getId(), new BigDecimal("1000.00"), new BigDecimal("990.00")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void acceptRequote_expiredNewQuote_requotesAgainInsteadOfPaying() {
        TransferExecutionEntity execution = requoteRequired();
        execution.setPayoutQuoteExpiresAt(NOW_UTC.plusSeconds(5));
        when(blindPayGateway.createQuote(any(), anyString()))
                .thenReturn(payoutQuote("qu_000000000003", "18012", "97000"));

        processor.acceptRequote(execution.getId(), company.getId(), new BigDecimal("1000.00"), new BigDecimal("980.00"));

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        assertThat(execution.getPayoutQuoteReceiverAmount()).isEqualByComparingTo("970.00");
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void payoutCompleted_isOnlyMarkedCompletedAfterProviderConfirmation() {
        // Arrange
        TransferExecutionEntity execution = payoutProcessing();
        when(blindPayGateway.getPayout("po_000000000001"))
                .thenReturn(payout("po_000000000001", "processing"))
                .thenReturn(payout("po_000000000001", "completed"));

        // Act
        processor.onPayoutEvent("po_000000000001");
        TransferExecutionStatus beforeConfirmation = execution.getStatus();
        processor.onPayoutEvent("po_000000000001");

        // Assert
        assertThat(beforeConfirmation).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(execution.getCompletedAt()).isEqualTo(NOW_UTC);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(transaction.getSettledAt()).isEqualTo(NOW_UTC);
    }

    @Test
    void payoutFailed_marksExecutionAndTransactionFailed() {
        TransferExecutionEntity execution = payoutProcessing();
        when(blindPayGateway.getPayout("po_000000000001")).thenReturn(payout("po_000000000001", "failed"));

        processor.onPayoutEvent("po_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYOUT_FAILED");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void payoutRefunded_marksExecutionRefunded() {
        TransferExecutionEntity execution = payoutProcessing();
        when(blindPayGateway.getPayout("po_000000000001")).thenReturn(payout("po_000000000001", "refunded"));

        processor.onPayoutEvent("po_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.CANCELED);
    }

    @Test
    void payinRefunded_neverSendsPayout() {
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001")).thenReturn(payin("pi_000000000001", "refunded", null));

        processor.onPayinEvent("pi_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYIN_REFUNDED");
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void duplicatedPayinCompletion_sendsPayoutOnlyOnce() {
        // Arrange
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenReturn(payout("po_000000000001", "processing"));

        // Act
        processor.onPayinEvent("pi_000000000001");
        processor.onPayinEvent("pi_000000000001");
        processor.reconcile(execution.getId());

        // Assert
        verify(blindPayGateway, times(1)).createEvmPayout(any(), anyString());
    }

    @Test
    void payoutWithoutResponse_thenRejected_goesToManualReconciliationInsteadOfRequoting() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_SUBMITTING);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        when(blindPayGateway.createEvmPayout(any(), anyString()))
                .thenThrow(BlindPayIntegrationException.communication("timeout", null))
                .thenThrow(rejected("QUOTE_ALREADY_USED"));

        // Act
        processor.start(execution.getId());
        String uncertainCode = execution.getFailureCode();
        processor.reconcile(execution.getId());

        // Assert
        assertThat(uncertainCode).isEqualTo("PAYOUT_UNCERTAIN");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        verify(blindPayGateway, never()).createQuote(any(), anyString());
        verify(blindPayGateway, times(2)).createEvmPayout(any(), eq(key(execution, "payout:0")));
    }

    @Test
    void payinUnknownToBackend_isIgnored() {
        assertThat(processor.onPayinEvent("pi_unknown")).isFalse();
        verify(blindPayGateway, never()).getPayin(anyString());
    }

    @Test
    void tokenExecution_sendsPayoutDirectlyFromAvailableBalance() {
        TransferExecutionEntity execution = tokenExecution();
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenReturn(payout("po_000000000009", "processing"));

        processor.start(execution.getId());

        ArgumentCaptor<BlindPayEvmPayoutRequest> request = ArgumentCaptor.forClass(BlindPayEvmPayoutRequest.class);
        verify(blindPayGateway).createEvmPayout(request.capture(), eq(key(execution, "payout:0")));
        assertThat(request.getValue().quoteId()).isEqualTo("qu_000000000009");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        verify(blindPayGateway, never()).createEvmPayin(anyString(), anyString());
    }

    @Test
    void tokenExecution_insufficientBalance_failsWithoutMovingFunds() {
        TransferExecutionEntity execution = tokenExecution();
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenThrow(rejected("PAYOUTS_INSUFFICIENT_BALANCE"));

        processor.start(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYOUTS_INSUFFICIENT_BALANCE");
    }

    @Test
    void tooManyRequotes_holdFundsForManualFollowUp() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        execution.setPayoutQuoteAttempts(BlindPayExecutionProcessor.MAX_PAYOUT_QUOTE_ATTEMPTS);

        processor.reconcile(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        verify(blindPayGateway, never()).createQuote(any(), anyString());
    }

    private TransferExecutionEntity awaitingFunds() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.AWAITING_FUNDS);
        execution.setExternalPayinId("pi_000000000001");
        return execution;
    }

    private TransferExecutionEntity requoteRequired() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.REQUOTE_REQUIRED);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setExternalPayoutQuoteId("qu_000000000002");
        execution.setPayoutQuoteReceiverAmount(new BigDecimal("980.00"));
        execution.setPayoutQuoteAttempts(1);
        return execution;
    }

    private TransferExecutionEntity payoutProcessing() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_PROCESSING);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setExternalPayoutId("po_000000000001");
        return execution;
    }

    private TransferExecutionEntity tokenExecution() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_SUBMITTING);
        execution.setSourceCurrency("USDB");
        execution.setSourceAmount(new BigDecimal("180.12"));
        execution.setExternalPayinQuoteId(null);
        execution.setExternalPayoutQuoteId("qu_000000000009");
        return execution;
    }

    private TransferExecutionEntity brlExecution(TransferExecutionStatus status) {
        TransferExecutionEntity execution = TransferExecutionEntity.builder()
                .id(UUID.randomUUID())
                .providerQuoteId(offer.getId())
                .companyId(company.getId())
                .requestedBy(UUID.randomUUID())
                .beneficiaryId(beneficiary.getId())
                .transactionId(transaction.getId())
                .provider(IntegrationProvider.BLINDPAY)
                .status(status)
                .sourceCurrency("BRL")
                .targetCurrency("USD")
                .sourceAmount(new BigDecimal("1000.00"))
                .targetAmount(new BigDecimal("990.00"))
                .externalPayinQuoteId("pq_000000000001")
                .externalPayoutQuoteId("qu_000000000001")
                .payoutQuoteSenderAmount(new BigDecimal("180.12"))
                .payoutQuoteReceiverAmount(new BigDecimal("990.00"))
                .payoutQuoteExpiresAt(NOW_UTC.plusMinutes(4))
                .createdAt(NOW_UTC)
                .updatedAt(NOW_UTC)
                .build();
        executions.put(execution.getId(), execution);
        return execution;
    }

    private static String key(TransferExecutionEntity execution, String step) {
        return "transfer-execution:" + execution.getId() + ":" + step;
    }

    private static BlindPayIntegrationException rejected(String code) {
        return BlindPayIntegrationException.response("rejected", 400, code, "trace", null);
    }

    private static BlindPayPayinResponse payin(String id, String status, BigDecimal receiverCents) {
        return new BlindPayPayinResponse(id, status, "re_000000000001", "pix", "pix-copy-paste", null, null,
                null, "BRL", "USDB", new BigDecimal("100000"), receiverCents, null, null, null, null, null,
                null, null);
    }

    private static BlindPayPayoutResponse payout(String id, String status) {
        return new BlindPayPayoutResponse(id, status, "re_000000000001", "ba_000000000001", null, "0xmanaged",
                "base_sepolia", "USDB", "USD", null, null, null, null, null, null, null, null, null, null);
    }

    private static BlindPayQuoteResponse payoutQuote(String id, String senderCents, String receiverCents) {
        return new BlindPayQuoteResponse(id, NOW.plusSeconds(300).toEpochMilli(), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal(receiverCents), new BigDecimal(senderCents), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, "Invoice 42", null);
    }
}
