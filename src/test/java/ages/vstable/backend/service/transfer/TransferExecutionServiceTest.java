package ages.vstable.backend.service.transfer;

import ages.vstable.backend.dto.transfer.ConfirmOfferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.ProviderQuoteEntity;
import ages.vstable.backend.entity.QuoteRequestEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.QuotePriceChangedException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.quote.ProviderQuoteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private ProviderQuoteRepository providerQuoteRepository;
    @Mock private TransferExecutionRepository executionRepository;
    @Mock private ImportTransactionRepository importTransactionRepository;
    @Mock private TransferService transferService;
    @Mock private BlindPayExecutionProcessor processor;

    private final Map<UUID, TransferExecutionEntity> executions = new HashMap<>();
    private TransferExecutionService service;
    private UserEntity user;
    private CompanyEntity company;
    private QuoteRequestEntity quoteRequest;
    private ProviderQuoteEntity offer;

    @BeforeEach
    void setUp() {
        service = new TransferExecutionService(providerQuoteRepository, executionRepository,
                importTransactionRepository, transferService, processor,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(company.getId()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        quoteRequest = QuoteRequestEntity.builder()
                .id(UUID.randomUUID()).company(company).requestedBy(user).beneficiary(beneficiary)
                .sourceCurrency("BRL").targetCurrency("USD").sourcePaymentMethod("pix").targetPaymentMethod("wire")
                .requestedAmount(new BigDecimal("1000.00")).amountSide(QuoteAmountSide.SOURCE)
                .token("USDB").blockchainNetwork("base_sepolia").description("Invoice 42").build();
        offer = ProviderQuoteEntity.builder()
                .id(UUID.randomUUID()).quoteRequest(quoteRequest).provider(IntegrationProvider.BLINDPAY)
                .externalQuoteId("qu_000000000001").status(ProviderQuoteStatus.SELECTED)
                .sourceAmount(new BigDecimal("1000.00")).targetAmount(new BigDecimal("990.00"))
                .exchangeRate(new BigDecimal("0.9900000000")).totalFee(new BigDecimal("2.50"))
                .expiresAt(NOW_UTC.plusMinutes(4))
                .providerMetadata("""
                        {"fundingQuoteId":"pq_000000000001","payoutQuoteId":"qu_000000000001",
                         "payoutSenderAmount":"180.12","payoutReceiverAmount":"990.00",
                         "payoutExpiresAt":%d,"walletId":"bl_000000000001"}
                        """.formatted(NOW.plusSeconds(250).toEpochMilli()))
                .build();

        when(providerQuoteRepository.findById(offer.getId())).thenReturn(Optional.of(offer));
        when(executionRepository.findByProviderQuoteId(any())).thenAnswer(call -> executions.values().stream()
                .filter(execution -> execution.getProviderQuoteId().equals(call.getArgument(0))).findFirst());
        when(executionRepository.saveAndFlush(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(executionRepository.save(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(executionRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(executions.get(call.getArgument(0))));
        when(executionRepository.findForUpdate(any())).thenAnswer(call -> Optional.ofNullable(executions.get(call.getArgument(0))));
        when(executionRepository.findByIdAndCompanyId(any(), any())).thenAnswer(call ->
                Optional.ofNullable(executions.get(call.getArgument(0)))
                        .filter(execution -> execution.getCompanyId().equals(call.getArgument(1))));
        when(importTransactionRepository.save(any())).thenAnswer(call -> {
            ImportTransactionEntity transaction = call.getArgument(0);
            transaction.setId(UUID.randomUUID());
            return transaction;
        });
    }

    @Test
    void confirm_blindPayBrlOffer_registersExecutionAndStartsPayin() {
        // Act
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        // Assert
        TransferExecutionEntity execution = executions.get(response.executionId());
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PENDING_SUBMISSION);
        assertThat(execution.getExternalPayinQuoteId()).isEqualTo("pq_000000000001");
        assertThat(execution.getExternalPayoutQuoteId()).isEqualTo("qu_000000000001");
        assertThat(execution.getPayoutQuoteSenderAmount()).isEqualByComparingTo("180.12");
        assertThat(execution.getPayoutQuoteExpiresAt()).isEqualTo(NOW_UTC.plusSeconds(250));
        assertThat(execution.getTransactionId()).isNotNull();
        assertThat(offer.getStatus()).isEqualTo(ProviderQuoteStatus.CONFIRMED);
        assertThat(response.provider()).isEqualTo("BLINDPAY");
        verify(processor).start(execution.getId());

        ArgumentCaptor<ImportTransactionEntity> transaction = ArgumentCaptor.forClass(ImportTransactionEntity.class);
        verify(importTransactionRepository).save(transaction.capture());
        assertThat(transaction.getValue().getStatus()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);
        assertThat(transaction.getValue().getTransferMethod()).isEqualTo(TransferMethod.PIX);
        assertThat(transaction.getValue().getSettlementAmountBrl()).isEqualByComparingTo("1000.00");
    }

    @Test
    void confirm_tokenFundedOffer_isSentAsPayoutFromAvailableBalance() {
        quoteRequest.setSourceCurrency("USDB");
        offer.setProviderMetadata("{}");

        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        TransferExecutionEntity execution = executions.get(response.executionId());
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.PAYOUT_SUBMITTING);
        assertThat(execution.getExternalPayinQuoteId()).isNull();
        assertThat(execution.getExternalPayoutQuoteId()).isEqualTo("qu_000000000001");
        ArgumentCaptor<ImportTransactionEntity> transaction = ArgumentCaptor.forClass(ImportTransactionEntity.class);
        verify(importTransactionRepository).save(transaction.capture());
        assertThat(transaction.getValue().getTransferMethod()).isEqualTo(TransferMethod.ACCOUNT_BALANCE);
        assertThat(transaction.getValue().getSettlementAmountBrl()).isNull();
        verify(processor).start(execution.getId());
    }

    @Test
    void confirm_sameOfferTwice_returnsSameTransferWithoutExecutingAgain() {
        TransferExecutionResponse first = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));
        TransferExecutionResponse second = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        assertThat(second.executionId()).isEqualTo(first.executionId());
        verify(processor, times(1)).start(any());
        verify(executionRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void confirm_concurrentConfirmationOfSameOffer_returnsExistingTransfer() {
        // Arrange: outra requisição registrou a execução entre a leitura e a gravação.
        TransferExecutionEntity existing = TransferExecutionEntity.builder()
                .id(UUID.randomUUID()).providerQuoteId(offer.getId()).companyId(company.getId())
                .provider(IntegrationProvider.BLINDPAY).status(TransferExecutionStatus.AWAITING_FUNDS)
                .sourceCurrency("BRL").targetCurrency("USD")
                .sourceAmount(new BigDecimal("1000.00")).targetAmount(new BigDecimal("990.00")).build();
        doReturn(Optional.empty()).doReturn(Optional.of(existing))
                .when(executionRepository).findByProviderQuoteId(offer.getId());
        doThrow(new DataIntegrityViolationException("duplicate")).when(executionRepository).saveAndFlush(any());

        // Act
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        // Assert
        assertThat(response.executionId()).isEqualTo(existing.getId());
        verifyNoInteractions(processor);
    }

    @Test
    void confirm_offerThatDidNotWinComparison_isRejected() {
        offer.setStatus(ProviderQuoteStatus.AVAILABLE);

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Only the best offer of a quote can be confirmed");
        verify(executionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(processor, transferService);
    }

    @Test
    void confirm_expiredOffer_isRejected() {
        offer.setExpiresAt(NOW_UTC.plusSeconds(5));

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("The offer has expired; request a new quote");
        verifyNoInteractions(processor, transferService);
    }

    @Test
    void confirm_amountsDifferentFromOffer_areRejected() {
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "995.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Confirmed amounts do not match the offer");
        verifyNoInteractions(processor, transferService);
    }

    @Test
    void confirm_offerFromAnotherCompany_isNotFound() {
        UserEntity outsider = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.confirm(outsider, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(processor, transferService);
    }

    @Test
    void confirm_aveniaOffer_executesWithPriceGuardAndLinksTicket() {
        // Arrange
        aveniaOffer();
        UUID transactionId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        when(transferService.createConfirmedOffer(any(), eq(user.getId()), eq(company.getId()), any()))
                .thenAnswer(call -> {
                    Consumer<TransferQuoteResponse> guard = call.getArgument(3);
                    guard.accept(aveniaQuote("1000.00", "180.50"));
                    return new TransferService.ExecutedTransfer(transactionId, TransactionStatus.AWAITING_PAYMENT, ticketId);
                });

        // Act
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "180.50"));

        // Assert
        assertThat(response.status()).isEqualTo(TransferExecutionStatus.SUBMITTED);
        assertThat(response.transactionId()).isEqualTo(transactionId);
        assertThat(executions.get(response.executionId()).getExternalTicketId()).isEqualTo(ticketId.toString());
        ArgumentCaptor<CreateTransferRequest> request = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createConfirmedOffer(request.capture(), any(), any(), any());
        assertThat(request.getValue().getPaymentMethod()).isEqualTo(TransferMethod.PIX);
        assertThat(request.getValue().getAmountType()).isEqualTo(TransferAmountType.SOURCE);
        assertThat(request.getValue().getAmount()).isEqualByComparingTo("1000.00");
        verifyNoInteractions(processor);
    }

    @Test
    void confirm_aveniaOfferAfterProviderTokenExpired_stillExecutesWithFreshQuote() {
        // Arrange: 30 s depois da cotação o quoteToken (15 s) já expirou, mas a oferta não.
        aveniaOffer();
        offer.setExpiresAt(NOW_UTC.plusSeconds(30));
        when(transferService.createConfirmedOffer(any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<TransferQuoteResponse> guard = call.getArgument(3);
            guard.accept(aveniaQuote("1000.00", "180.50"));
            return new TransferService.ExecutedTransfer(UUID.randomUUID(), TransactionStatus.AWAITING_PAYMENT,
                    UUID.randomUUID());
        });

        // Act
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "180.50"));

        // Assert
        assertThat(response.status()).isEqualTo(TransferExecutionStatus.SUBMITTED);
    }

    @Test
    void confirm_aveniaPriceGotWorse_failsWithConflictBeforeCreatingTicket() {
        // Arrange
        aveniaOffer();
        when(transferService.createConfirmedOffer(any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<TransferQuoteResponse> guard = call.getArgument(3);
            guard.accept(aveniaQuote("1000.00", "179.90"));
            throw new AssertionError("ticket must not be created");
        });

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "180.50")))
                .isInstanceOf(QuotePriceChangedException.class);
        TransferExecutionEntity execution = executions.values().iterator().next();
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(execution.getFailureCode()).isEqualTo(TransferExecutionService.FAILURE_PRICE_CHANGED);
    }

    @Test
    void confirm_aveniaFailsAfterTicketRequest_requiresReconciliationAndIsNotRetried() {
        aveniaOffer();
        when(transferService.createConfirmedOffer(any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<TransferQuoteResponse> guard = call.getArgument(3);
            guard.accept(aveniaQuote("1000.00", "180.50"));
            throw new AveniaIntegrationException("timeout", 503, true, null);
        });

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "180.50")))
                .isInstanceOf(AveniaIntegrationException.class);
        TransferExecutionEntity execution = executions.values().iterator().next();
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);

        // Repetir a confirmação devolve a execução existente; não chama a Avenia de novo.
        TransferExecutionResponse replay = service.confirm(user, offer.getId(), confirmation("1000.00", "180.50"));
        assertThat(replay.status()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        verify(transferService, times(1)).createConfirmedOffer(any(), any(), any(), any());
    }

    @Test
    void confirm_aveniaFailsBeforeTicket_isMarkedFailed() {
        aveniaOffer();
        when(transferService.createConfirmedOffer(any(), any(), any(), any()))
                .thenThrow(new AveniaIntegrationException("quote unavailable", 503, true, null));

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "180.50")))
                .isInstanceOf(AveniaIntegrationException.class);
        assertThat(executions.values().iterator().next().getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
    }

    @Test
    void find_executionOfAnotherCompany_isNotFound() {
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));
        UserEntity outsider = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.find(outsider, response.executionId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void find_awaitingFunds_exposesPixInstructions() {
        TransferExecutionResponse confirmed = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));
        TransferExecutionEntity execution = executions.get(confirmed.executionId());
        execution.setStatus(TransferExecutionStatus.AWAITING_FUNDS);
        execution.setFundingInstructions("pix-copy-paste");

        TransferExecutionResponse response = service.find(user, execution.getId());

        assertThat(response.fundingInstructions()).isEqualTo("pix-copy-paste");
        assertThat(response.requote()).isNull();
    }

    private void aveniaOffer() {
        offer.setProvider(IntegrationProvider.AVENIA);
        offer.setExternalQuoteId(null);
        offer.setTargetAmount(new BigDecimal("180.50"));
        offer.setProviderMetadata("{}");
        quoteRequest.setTargetCurrency("USDC");
    }

    private TransferExecutionEntity store(TransferExecutionEntity execution) {
        if (execution.getId() == null) {
            execution.setId(UUID.randomUUID());
        }
        executions.put(execution.getId(), execution);
        return execution;
    }

    private static ConfirmOfferRequest confirmation(String source, String target) {
        return new ConfirmOfferRequest(new BigDecimal(source), new BigDecimal(target));
    }

    private static TransferQuoteResponse aveniaQuote(String source, String destination) {
        return new TransferQuoteResponse(
                new MoneyAmount(new BigDecimal(source), "BRL"),
                new MoneyAmount(new BigDecimal(destination), "USDC"),
                new ExchangeRateInfo("USDC", "BRL", new BigDecimal("5.5")),
                new FeeInfo(BigDecimal.ZERO, BigDecimal.ZERO, "BRL"),
                new MoneyAmount(new BigDecimal(source), "BRL"));
    }
}
