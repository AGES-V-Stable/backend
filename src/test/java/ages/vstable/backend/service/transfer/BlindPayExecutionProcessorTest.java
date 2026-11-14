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
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
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
import org.springframework.transaction.TransactionDefinition;

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
import static org.mockito.Mockito.atLeastOnce;
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
    @Mock private PlatformTransactionManager transactionManager;

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
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));

        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        QuoteRequestEntity request = QuoteRequestEntity.builder()
                .id(UUID.randomUUID()).company(company).beneficiary(beneficiary)
                .token("USDB").blockchainNetwork("base_sepolia").description("Invoice 42").build();
        offer = ProviderQuoteEntity.builder().id(UUID.randomUUID()).quoteRequest(request)
                .provider(IntegrationProvider.BLINDPAY).build();
        transaction = BaseTransactionEntity.builder().id(UUID.randomUUID())
                .status(TransactionStatus.AWAITING_PAYMENT).foreignAmount(new BigDecimal("990.00"))
                .updatedAt(NOW_UTC.minusMinutes(10)).build();

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
        execution.setFailureCode("PREV_CODE");
        execution.setFailureMessage("Previous transient failure");
        when(blindPayGateway.createEvmPayin("pq_000000000001", key(execution, "payin")))
                .thenReturn(payin("pi_000000000001", "processing", null));

        // Act
        processor.start(execution.getId());

        // Assert
        ArgumentCaptor<TransactionDefinition> txDef = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, atLeastOnce()).getTransaction(txDef.capture());
        assertThat(txDef.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(execution.getExternalPayinId()).isEqualTo("pi_000000000001");
        assertThat(execution.getFundingInstructions()).isEqualTo("pix-copy-paste");
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
        assertThat(execution.getUpdatedAt()).isEqualTo(NOW_UTC);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);
        assertThat(transaction.getUpdatedAt()).isEqualTo(NOW_UTC);
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void start_brlExecutionWithImmediatelyCompletedPayin_appliesPayinAndSubmitsPayout() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PENDING_SUBMISSION);
        when(blindPayGateway.createEvmPayin("pq_000000000001", key(execution, "payin")))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createEvmPayout(any(), anyString()))
                .thenReturn(payout("po_000000000001", "completed"));

        // Act
        processor.start(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(execution.getFundedAmount()).isEqualByComparingTo("180.12");
        assertThat(execution.getExternalPayoutId()).isEqualTo("po_000000000001");
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
        String failureCodeAfterTimeout = execution.getFailureCode();
        String failureMsgAfterTimeout = execution.getFailureMessage();
        processor.reconcile(execution.getId());

        // Assert
        assertThat(afterTimeout).isEqualTo(TransferExecutionStatus.PENDING_SUBMISSION);
        assertThat(failureCodeAfterTimeout).isEqualTo("PAYIN_UNCERTAIN");
        assertThat(failureMsgAfterTimeout).isEqualTo("timeout");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
        verify(blindPayGateway, times(2)).createEvmPayin("pq_000000000001", key(execution, "payin"));
    }

    @Test
    void start_payinRejectedByProvider_failsWithoutRetrying() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PENDING_SUBMISSION);
        when(blindPayGateway.createEvmPayin(anyString(), anyString())).thenThrow(rejected("QUOTE_EXPIRED"));

        processor.start(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(execution.getFailureMessage()).isEqualTo("rejected");
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
        execution.setFailureCode("PREV_PAYIN_ERR");
        execution.setFailureMessage("Previous payin sync error");
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenAnswer(inv -> {
            assertThat(execution.getFailureCode()).isNull();
            assertThat(execution.getFailureMessage()).isNull();
            return payout("po_000000000001", "processing");
        });

        // Act
        processor.onPayinEvent("pi_000000000001");

        // Assert
        ArgumentCaptor<BlindPayEvmPayoutRequest> request = ArgumentCaptor.forClass(BlindPayEvmPayoutRequest.class);
        verify(blindPayGateway).createEvmPayout(request.capture(), eq(key(execution, "payout:0")));
        assertThat(request.getValue().quoteId()).isEqualTo("qu_000000000001");
        assertThat(request.getValue().senderWalletAddress()).isEqualTo("0xmanaged");
        assertThat(request.getValue().walletId()).isNull();
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
                .thenReturn(payoutQuote("qu_000000000002", "17950", "99500"));
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
        assertThat(execution.getPayoutQuoteSenderAmount()).isEqualByComparingTo("179.50");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
    }

    @Test
    void payinCompleted_requoteDeliversLess_requiresNewConfirmationAndDoesNotPay() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        execution.setFailureCode("PREV_QUOTE_ERR");
        execution.setFailureMessage("Previous quote timeout");
        when(blindPayGateway.createQuote(any(), anyString()))
                .thenReturn(payoutQuote("qu_000000000002", "17950", "98000"));

        // Act
        processor.reconcile(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REQUOTE_REQUIRED);
        assertThat(execution.getPayoutQuoteSenderAmount()).isEqualByComparingTo("179.50");
        assertThat(execution.getPayoutQuoteReceiverAmount()).isEqualByComparingTo("980.00");
        assertThat(execution.getTargetAmount()).isEqualByComparingTo("990.00");
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
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
        verify(executionRepository, times(2)).save(execution);
        verify(blindPayGateway).createEvmPayout(any(), eq(key(execution, "payout:1")));
    }

    @Test
    void acceptRequote_unknownTransfer_throwsNotFound() {
        assertThatThrownBy(() -> processor.acceptRequote(
                UUID.randomUUID(), company.getId(), new BigDecimal("1000.00"), new BigDecimal("980.00")))
                .isInstanceOf(NotFoundException.class);
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
        execution.setFailureCode("PREV_SYNC_ERR");
        execution.setFailureMessage("Previous payout sync error");
        when(blindPayGateway.getPayout("po_000000000001"))
                .thenReturn(payout("po_000000000001", "processing"))
                .thenReturn(payout("po_000000000001", "completed"));

        // Act
        boolean firstHandled = processor.onPayoutEvent("po_000000000001");
        TransferExecutionStatus beforeConfirmation = execution.getStatus();
        boolean secondHandled = processor.onPayoutEvent("po_000000000001");

        // Assert
        assertThat(firstHandled).isTrue();
        assertThat(secondHandled).isTrue();
        assertThat(beforeConfirmation).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(execution.getCompletedAt()).isEqualTo(NOW_UTC);
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
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
        assertThat(execution.getFailureMessage()).isEqualTo("BlindPay payout failed");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void payoutRefunded_marksExecutionRefunded() {
        TransferExecutionEntity execution = payoutProcessing();
        when(blindPayGateway.getPayout("po_000000000001")).thenReturn(payout("po_000000000001", "refunded"));

        processor.onPayoutEvent("po_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYOUT_REFUNDED");
        assertThat(execution.getFailureMessage()).isEqualTo("BlindPay payout was refunded");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.CANCELED);
    }

    @Test
    void payinRefunded_neverSendsPayout() {
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001")).thenReturn(payin("pi_000000000001", "refunded", null));

        processor.onPayinEvent("pi_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYIN_REFUNDED");
        assertThat(execution.getFailureMessage()).isEqualTo("BlindPay payin was refunded");
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void payinFailed_marksExecutionFailed() {
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001")).thenReturn(payin("pi_000000000001", "failed", null));

        processor.onPayinEvent("pi_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYIN_FAILED");
        assertThat(execution.getFailureMessage()).isEqualTo("BlindPay payin failed");
    }

    @Test
    void payinCompleted_zeroReceiverAmount_throwsInvalidResponse() {
        TransferExecutionEntity execution = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", BigDecimal.ZERO));

        processor.onPayinEvent("pi_000000000001");

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(execution.getFailureMessage()).isEqualTo("BlindPay payin has no receiver amount");
        verify(blindPayGateway, never()).createEvmPayout(any(), anyString());
    }

    @Test
    void payinCompleted_whenAlreadyFundsReceived_doesNotAdvanceFromPayinTransition() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        execution.setExternalPayinId("pi_000000000001");
        execution.setFundedAmount(new BigDecimal("180.12"));
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenReturn(payin("pi_000000000001", "completed", new BigDecimal("18012")));

        // Act
        processor.onPayinEvent("pi_000000000001");

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FUNDS_RECEIVED);
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
    void payoutRejected_withExpiredQuoteAndNoPriorUncertainAttempt_requotesAndAdvances() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_SUBMITTING);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        when(blindPayGateway.createEvmPayout(any(), eq(key(execution, "payout:0"))))
                .thenThrow(rejected("QUOTE_EXPIRED"));
        when(blindPayGateway.createQuote(any(), eq(key(execution, "payout-quote:1")))).thenAnswer(inv -> {
            assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FUNDS_RECEIVED);
            assertThat(execution.getFailureCode()).isEqualTo("QUOTE_EXPIRED");
            assertThat(execution.getFailureMessage()).isEqualTo("rejected");
            return payoutQuote("qu_000000000002", "18012", "99500");
        });
        when(blindPayGateway.createEvmPayout(any(), eq(key(execution, "payout:1"))))
                .thenReturn(payout("po_000000000001", "processing"));

        // Act
        processor.start(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        verify(blindPayGateway).createQuote(any(), eq(key(execution, "payout-quote:1")));
        verify(blindPayGateway).createEvmPayout(any(), eq(key(execution, "payout:1")));
    }

    @Test
    void payoutRejected_withUsableQuote_goesToManualReconciliation() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_SUBMITTING);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.plusMinutes(5));
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenThrow(rejected("BANK_ACCOUNT_REJECTED"));

        // Act
        processor.start(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(execution.getFailureCode()).isEqualTo("BANK_ACCOUNT_REJECTED");
        assertThat(execution.getFailureMessage()).isEqualTo("rejected");
        verify(blindPayGateway, never()).createQuote(any(), anyString());
    }

    @Test
    void payoutRejected_dueToComplianceError_goesToManualReconciliationEvenWhenQuoteExpired() {
        // Arrange
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.PAYOUT_SUBMITTING);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        when(provisioningService.ensureManagedWallet(company, "base_sepolia"))
                .thenThrow(new BlindPayComplianceException("KYB suspended"));

        // Act
        processor.start(execution.getId());

        // Assert
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(execution.getFailureCode()).isEqualTo("COMPLIANCE_NOT_APPROVED");
        assertThat(execution.getFailureMessage()).isEqualTo("KYB suspended");
        verify(blindPayGateway, never()).createQuote(any(), anyString());
    }

    @Test
    void payinUnknownToBackend_isIgnored() {
        assertThat(processor.onPayinEvent("pi_unknown")).isFalse();
        assertThat(processor.onPayoutEvent("po_unknown")).isFalse();
        verify(blindPayGateway, never()).getPayin(anyString());
        verify(blindPayGateway, never()).getPayout(anyString());
    }

    @Test
    void tokenExecution_sendsPayoutDirectlyFromAvailableBalance() {
        TransferExecutionEntity execution = tokenExecution();
        execution.setFailureCode("PREV_ERR");
        execution.setFailureMessage("Previous error");
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenReturn(payout("po_000000000009", "processing"));

        processor.start(execution.getId());

        ArgumentCaptor<BlindPayEvmPayoutRequest> request = ArgumentCaptor.forClass(BlindPayEvmPayoutRequest.class);
        verify(blindPayGateway).createEvmPayout(request.capture(), eq(key(execution, "payout:0")));
        assertThat(request.getValue().quoteId()).isEqualTo("qu_000000000009");
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_PROCESSING);
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
        verify(blindPayGateway, never()).createEvmPayin(anyString(), anyString());
    }

    @Test
    void tokenExecution_insufficientBalance_failsWithoutMovingFunds() {
        TransferExecutionEntity execution = tokenExecution();
        when(blindPayGateway.createEvmPayout(any(), anyString())).thenThrow(rejected("PAYOUTS_INSUFFICIENT_BALANCE"));

        processor.start(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYOUTS_INSUFFICIENT_BALANCE");
        assertThat(execution.getFailureMessage()).isEqualTo("rejected");
    }

    @Test
    void tooManyRequotes_holdFundsForManualFollowUp() {
        TransferExecutionEntity execution = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        execution.setFundedAmount(new BigDecimal("180.12"));
        execution.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));
        execution.setPayoutQuoteAttempts(BlindPayExecutionProcessor.MAX_PAYOUT_QUOTE_ATTEMPTS);

        processor.reconcile(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(execution.getFailureCode()).isEqualTo("PAYOUT_QUOTE_ATTEMPTS_EXCEEDED");
        assertThat(execution.getFailureMessage())
                .isEqualTo("Funds are held in the BlindPay wallet; payout needs manual follow-up");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.HELD);
        verify(executionRepository).save(execution);
        verify(blindPayGateway, never()).createQuote(any(), anyString());
    }

    @Test
    void reconcile_awaitingFundsAndPayoutProcessing_syncsFromProviderAndRecordsFailures() {
        // Arrange
        TransferExecutionEntity awaiting = awaitingFunds();
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenThrow(BlindPayIntegrationException.response("payin lookup error", 502, "UPSTREAM_502", "t1", null))
                .thenReturn(payin("pi_000000000001", "refunded", null));

        TransferExecutionEntity processing = payoutProcessing();
        processing.setFailureCode("PAYOUT_UNCERTAIN");
        when(blindPayGateway.getPayout("po_000000000001"))
                .thenThrow(BlindPayIntegrationException.response("payout lookup error", 502, "PAYOUT_502", "t2", null))
                .thenReturn(payout("po_000000000001", "completed"));

        // Act & Assert - awaiting funds error then success
        processor.reconcile(awaiting.getId());
        assertThat(awaiting.getStatus()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(awaiting.getFailureCode()).isEqualTo("UPSTREAM_502");
        assertThat(awaiting.getFailureMessage()).isEqualTo("payin lookup error");

        processor.reconcile(awaiting.getId());
        assertThat(awaiting.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);

        // Act & Assert - payout processing error overwrites PAYOUT_UNCERTAIN since not PAYOUT_SUBMITTING, then completes
        processor.onPayoutEvent("po_000000000001");
        assertThat(processing.getFailureCode()).isEqualTo("PAYOUT_502");
        assertThat(processing.getFailureMessage()).isEqualTo("payout lookup error");

        processor.reconcile(processing.getId());
        assertThat(processing.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
    }

    @Test
    void requotePayout_handlesComplianceIllegalArgumentIntegrationAndIncompleteQuotes() {
        // Arrange
        TransferExecutionEntity complianceExec = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        complianceExec.setFundedAmount(new BigDecimal("180.12"));
        complianceExec.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));

        TransferExecutionEntity illegalArgExec = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        illegalArgExec.setFundedAmount(new BigDecimal("180.12"));
        illegalArgExec.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));

        TransferExecutionEntity integrationExec = brlExecution(TransferExecutionStatus.FUNDS_RECEIVED);
        integrationExec.setFundedAmount(new BigDecimal("180.12"));
        integrationExec.setPayoutQuoteExpiresAt(NOW_UTC.minusSeconds(1));

        when(blindPayGateway.createQuote(any(), eq(key(complianceExec, "payout-quote:1"))))
                .thenThrow(new BlindPayComplianceException("KYB blocked"));
        when(blindPayGateway.createQuote(any(), eq(key(illegalArgExec, "payout-quote:1"))))
                .thenThrow(new IllegalArgumentException("Amount too small"));
        when(blindPayGateway.createQuote(any(), eq(key(integrationExec, "payout-quote:1"))))
                .thenThrow(BlindPayIntegrationException.response("quote err", 500, "QUOTE_500", "t", null))
                .thenReturn(payoutQuote("qu_zero_sender", "0", "99000"))
                .thenReturn(payoutQuote("qu_zero_receiver", "18012", "0"));

        // Act & Assert
        processor.reconcile(complianceExec.getId());
        assertThat(complianceExec.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(complianceExec.getFailureCode()).isEqualTo("COMPLIANCE_NOT_APPROVED");
        assertThat(complianceExec.getFailureMessage()).isEqualTo("KYB blocked");

        processor.reconcile(illegalArgExec.getId());
        assertThat(illegalArgExec.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        assertThat(illegalArgExec.getFailureCode()).isEqualTo("INVALID_PAYOUT_REQUEST");
        assertThat(illegalArgExec.getFailureMessage()).isEqualTo("Amount too small");

        processor.reconcile(integrationExec.getId());
        assertThat(integrationExec.getStatus()).isEqualTo(TransferExecutionStatus.FUNDS_RECEIVED);
        assertThat(integrationExec.getFailureCode()).isEqualTo("QUOTE_500");
        assertThat(integrationExec.getFailureMessage()).isEqualTo("quote err");

        processor.reconcile(integrationExec.getId());
        assertThat(integrationExec.getFailureCode()).isNull();
        assertThat(integrationExec.getFailureMessage()).isEqualTo("BlindPay returned an incomplete payout quote");

        processor.reconcile(integrationExec.getId());
        assertThat(integrationExec.getFailureMessage()).isEqualTo("BlindPay returned an incomplete payout quote");
    }

    @Test
    void recordFailure_ignoresTerminalExecutionAndTruncatesAtBoundary() {
        // Arrange
        TransferExecutionEntity completed = brlExecution(TransferExecutionStatus.COMPLETED);
        completed.setExternalPayinId("pi_completed");
        when(blindPayGateway.getPayin("pi_completed"))
                .thenThrow(BlindPayIntegrationException.communication("should be ignored", null));

        TransferExecutionEntity active = awaitingFunds();
        String msg500 = "a".repeat(500);
        String msg501 = "b".repeat(501);
        when(blindPayGateway.getPayin("pi_000000000001"))
                .thenThrow(BlindPayIntegrationException.response(msg500, 400, "   ", "t", null))
                .thenThrow(BlindPayIntegrationException.response(msg501, 400, null, "t", null));

        // Act & Assert - terminal execution is untouched
        processor.onPayinEvent("pi_completed");
        assertThat(completed.getFailureCode()).isNull();
        assertThat(completed.getFailureMessage()).isNull();

        // Act & Assert - 500-char message kept in full, 501-char message truncated to 500
        processor.onPayinEvent("pi_000000000001");
        assertThat(active.getFailureMessage()).isEqualTo(msg500);

        processor.onPayinEvent("pi_000000000001");
        assertThat(active.getFailureMessage()).hasSize(500).isEqualTo(msg501.substring(0, 500));

        // Unknown execution id throws NotFoundException
        assertThatThrownBy(() -> processor.start(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
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
                .updatedAt(NOW_UTC.minusMinutes(10))
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
