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
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.QuotePriceChangedException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.TwoFactorAuthService;
import ages.vstable.backend.service.quote.ProviderQuoteStatus;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private static final String TOTP = "123456";

    @Mock private ProviderQuoteRepository providerQuoteRepository;
    @Mock private TransferExecutionRepository executionRepository;
    @Mock private ImportTransactionRepository importTransactionRepository;
    @Mock private TransferService transferService;
    @Mock private BlindPayExecutionProcessor processor;
    @Mock private TwoFactorAuthService twoFactorAuthService;
    @Mock private TwoFactorAuthService.TotpReservation reservation;

    private final Map<UUID, TransferExecutionEntity> executions = new HashMap<>();
    private final InMemoryTransactionManager transactionManager = new InMemoryTransactionManager();
    private TransferExecutionService service;
    private UserEntity user;
    private CompanyEntity company;
    private QuoteRequestEntity quoteRequest;
    private ProviderQuoteEntity offer;

    @BeforeEach
    void setUp() {
        service = new TransferExecutionService(providerQuoteRepository, executionRepository,
                importTransactionRepository, transferService, processor, twoFactorAuthService,
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));

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

        when(providerQuoteRepository.findForUpdate(offer.getId())).thenReturn(Optional.of(offer));
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
        when(twoFactorAuthService.reserveTransferTotp(any(), anyString())).thenReturn(reservation);
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
    void confirm_validTotp_isReservedBeforeAnyWriteAndStaysConsumedAfterCommit() {
        // Act
        service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        // Assert
        InOrder order = inOrder(providerQuoteRepository, twoFactorAuthService, importTransactionRepository,
                executionRepository, processor);
        order.verify(providerQuoteRepository).findForUpdate(offer.getId());
        order.verify(twoFactorAuthService).reserveTransferTotp(user.getId(), TOTP);
        order.verify(importTransactionRepository).save(any());
        order.verify(executionRepository).saveAndFlush(any());
        order.verify(processor).start(any());
        verify(reservation, never()).release();
    }

    static Stream<RuntimeException> totpRejections() {
        return Stream.of(
                new BadCredentialsException("Invalid 2FA verification code"),
                new BadCredentialsException("TOTP code has already been used"),
                new UnprocessableEntityException("Usuário não possui 2FA ativo"),
                new LockedException("Too many failed 2FA attempts. Try again later."));
    }

    @ParameterizedTest
    @MethodSource("totpRejections")
    void confirm_totpRejected_persistsNothingAndCallsNoProvider(RuntimeException rejection) {
        // Arrange: código incorreto, reutilizado, conta sem 2FA ou verificação bloqueada.
        when(twoFactorAuthService.reserveTransferTotp(user.getId(), TOTP)).thenThrow(rejection);

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isSameAs(rejection);
        assertNothingWasExecuted();
    }

    @Test
    void confirm_aveniaOfferWithRejectedTotp_neverCallsAvenia() {
        // Arrange
        aveniaOffer();
        when(twoFactorAuthService.reserveTransferTotp(user.getId(), TOTP))
                .thenThrow(new BadCredentialsException("Invalid 2FA verification code"));

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "180.50")))
                .isInstanceOf(BadCredentialsException.class);
        assertNothingWasExecuted();
    }

    @Test
    void confirm_failureAfterTotpReservation_rollsBackAndReleasesTheCode() {
        // Arrange: metadados da BlindPay inválidos são detectados depois da reserva do código.
        offer.setProviderMetadata("{}");

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(BlindPayIntegrationException.class);
        verify(reservation).release();
        verify(executionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(processor, transferService);
    }

    @Test
    void confirm_commitWithUnknownOutcome_keepsTheCodeConsumed() {
        // Arrange: a confirmação pode ter sido gravada; devolver o código permitiria reutilizá-lo.
        transactionManager.failNextCommit = true;

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(TransactionSystemException.class);
        verify(reservation, never()).release();
        verifyNoInteractions(processor, transferService);
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
        // A repetição (ex.: reenvio após timeout) chega com o mesmo código, que já foi consumido.
        TransferExecutionResponse second = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        assertThat(second.executionId()).isEqualTo(first.executionId());
        verify(processor, times(1)).start(any());
        verify(executionRepository, times(1)).saveAndFlush(any());
        verify(twoFactorAuthService, times(1)).reserveTransferTotp(any(), anyString());
    }

    @Test
    void confirm_repeatedByAnotherMemberOfTheCompany_isConflictWithoutExecutingAgain() {
        // Arrange
        service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));
        UserEntity colleague = UserEntity.builder().id(UUID.randomUUID()).companyId(company.getId()).build();

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(colleague, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("OFFER_ALREADY_CONFIRMED"));
        verify(twoFactorAuthService, never()).reserveTransferTotp(eq(colleague.getId()), anyString());
        verify(processor, times(1)).start(any());
        verify(executionRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void confirm_repeatedWithDifferentAmounts_isRejected() {
        service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "995.00")))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(processor, times(1)).start(any());
    }

    @Test
    void confirm_concurrentConfirmationOfSameOffer_returnsExistingTransferAndReleasesTheCode() {
        // Arrange: outra requisição registrou a execução entre a leitura e a gravação.
        TransferExecutionEntity existing = existingExecution(user.getId());
        doReturn(Optional.empty()).doReturn(Optional.of(existing))
                .when(executionRepository).findByProviderQuoteId(offer.getId());
        doThrow(new DataIntegrityViolationException("duplicate")).when(executionRepository).saveAndFlush(any());

        // Act
        TransferExecutionResponse response = service.confirm(user, offer.getId(), confirmation("1000.00", "990.00"));

        // Assert
        assertThat(response.executionId()).isEqualTo(existing.getId());
        verify(reservation).release();
        verifyNoInteractions(processor);
    }

    @Test
    void confirm_concurrentConfirmationByAnotherMember_isConflictAndReleasesTheCode() {
        // Arrange
        TransferExecutionEntity existing = existingExecution(UUID.randomUUID());
        doReturn(Optional.empty()).doReturn(Optional.of(existing))
                .when(executionRepository).findByProviderQuoteId(offer.getId());
        doThrow(new DataIntegrityViolationException("duplicate")).when(executionRepository).saveAndFlush(any());

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(ConflictException.class);
        verify(reservation).release();
        verifyNoInteractions(processor);
    }

    @Test
    void confirm_offerThatDidNotWinComparison_isRejected() {
        offer.setStatus(ProviderQuoteStatus.AVAILABLE);

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Only the best offer of a quote can be confirmed");
        verify(executionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(processor, transferService, twoFactorAuthService);
    }

    @Test
    void confirm_expiredOffer_isRejected() {
        offer.setExpiresAt(NOW_UTC.plusSeconds(5));

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("The offer has expired; request a new quote");
        verifyNoInteractions(processor, transferService, twoFactorAuthService);
    }

    @Test
    void confirm_amountsDifferentFromOffer_areRejected() {
        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "995.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Confirmed amounts do not match the offer");
        verifyNoInteractions(processor, transferService, twoFactorAuthService);
    }

    @Test
    void confirm_offerFromAnotherCompany_isNotFound() {
        UserEntity outsider = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.confirm(outsider, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(processor, transferService, twoFactorAuthService);
        assertThat(offer.getStatus()).isEqualTo(ProviderQuoteStatus.SELECTED);
    }

    @Test
    void confirm_removedMember_isNotFoundWithoutAskingForTheCode() {
        // Arrange: defesa em profundidade; o controller já recusa o vínculo removido com 403.
        UserEntity removed = TestCompanyUsers.removedMember(company.getId(), CompanyRole.OPERATOR);

        // Act / Assert
        assertThatThrownBy(() -> service.confirm(removed, offer.getId(), confirmation("1000.00", "990.00")))
                .isInstanceOf(NotFoundException.class);
        assertNothingWasExecuted();
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
        // O código do orquestrador não é repassado: a rota legada não o valida nem consome de novo.
        assertThat(request.getValue().getTotpCode()).isNull();
        InOrder order = inOrder(twoFactorAuthService, transferService);
        order.verify(twoFactorAuthService).reserveTransferTotp(user.getId(), TOTP);
        order.verify(transferService).createConfirmedOffer(any(), any(), any(), any());
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
        // A confirmação foi registrada antes do timeout: o código permanece consumido.
        verify(reservation, never()).release();

        // Repetir a confirmação devolve a execução existente; não chama a Avenia de novo.
        TransferExecutionResponse replay = service.confirm(user, offer.getId(), confirmation("1000.00", "180.50"));
        assertThat(replay.status()).isEqualTo(TransferExecutionStatus.RECONCILIATION_REQUIRED);
        verify(transferService, times(1)).createConfirmedOffer(any(), any(), any(), any());
        verify(twoFactorAuthService, times(1)).reserveTransferTotp(any(), anyString());
    }

    @Test
    void confirm_aveniaFailsBeforeTicket_isMarkedFailed() {
        aveniaOffer();
        when(transferService.createConfirmedOffer(any(), any(), any(), any()))
                .thenThrow(new AveniaIntegrationException("quote unavailable", 503, true, null));

        assertThatThrownBy(() -> service.confirm(user, offer.getId(), confirmation("1000.00", "180.50")))
                .isInstanceOf(AveniaIntegrationException.class);
        assertThat(executions.values().iterator().next().getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        verify(reservation, never()).release();
    }

    @Test
    void confirmRequote_validTotp_isRequiredInsideTheProcessorTransaction() {
        // Arrange
        TransferExecutionEntity execution = store(existingExecution(user.getId()));
        authorizeRequoteInsideTransaction(null);

        // Act
        TransferExecutionResponse response = service.confirmRequote(user, execution.getId(),
                confirmation("1000.00", "980.00"));

        // Assert
        assertThat(response.executionId()).isEqualTo(execution.getId());
        verify(processor).acceptRequote(eq(execution.getId()), eq(company.getId()),
                eq(new BigDecimal("1000.00")), eq(new BigDecimal("980.00")), any());
        verify(twoFactorAuthService).reserveTransferTotp(user.getId(), TOTP);
        verify(reservation, never()).release();
    }

    @Test
    void confirmRequote_rejectedTotp_propagatesWithoutReadingTheExecution() {
        // Arrange
        TransferExecutionEntity execution = store(existingExecution(user.getId()));
        when(twoFactorAuthService.reserveTransferTotp(user.getId(), TOTP))
                .thenThrow(new BadCredentialsException("TOTP code has already been used"));
        authorizeRequoteInsideTransaction(null);

        // Act / Assert
        assertThatThrownBy(() -> service.confirmRequote(user, execution.getId(), confirmation("1000.00", "980.00")))
                .isInstanceOf(BadCredentialsException.class);
        verify(executionRepository, never()).findByIdAndCompanyId(any(), any());
    }

    @Test
    void confirmRequote_rollbackAfterTheCodeIsReserved_releasesTheCode() {
        // Arrange: a transação da recotação é desfeita depois da autorização.
        TransferExecutionEntity execution = store(existingExecution(user.getId()));
        authorizeRequoteInsideTransaction(new IllegalStateException("database unavailable"));

        // Act / Assert
        assertThatThrownBy(() -> service.confirmRequote(user, execution.getId(), confirmation("1000.00", "980.00")))
                .isInstanceOf(IllegalStateException.class);
        verify(reservation).release();
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

    @Test
    void confirmOfferRequest_toStringDoesNotExposeTheCode() {
        assertThat(confirmation("1000.00", "990.00").toString()).doesNotContain(TOTP).contains("totpCode=***");
    }

    private void assertNothingWasExecuted() {
        assertThat(executions).isEmpty();
        assertThat(offer.getStatus()).isEqualTo(ProviderQuoteStatus.SELECTED);
        verify(providerQuoteRepository, never()).save(any());
        verify(importTransactionRepository, never()).save(any());
        verify(executionRepository, never()).saveAndFlush(any());
        verify(reservation, never()).release();
        verifyNoInteractions(processor, transferService);
    }

    /**
     * Simula o processador real: roda a autorização em uma transação própria e, opcionalmente,
     * falha depois dela, desfazendo a transação.
     */
    private void authorizeRequoteInsideTransaction(RuntimeException failureAfterAuthorization) {
        doAnswer(call -> {
            Runnable authorization = call.getArgument(4);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                authorization.run();
                if (failureAfterAuthorization != null) {
                    throw failureAfterAuthorization;
                }
            });
            return null;
        }).when(processor).acceptRequote(any(), any(), any(), any(), any());
    }

    private TransferExecutionEntity existingExecution(UUID requestedBy) {
        return TransferExecutionEntity.builder()
                .id(UUID.randomUUID()).providerQuoteId(offer.getId()).companyId(company.getId())
                .requestedBy(requestedBy)
                .provider(IntegrationProvider.BLINDPAY).status(TransferExecutionStatus.AWAITING_FUNDS)
                .sourceCurrency("BRL").targetCurrency("USD")
                .sourceAmount(new BigDecimal("1000.00")).targetAmount(new BigDecimal("990.00")).build();
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
        return new ConfirmOfferRequest(new BigDecimal(source), new BigDecimal(target), TOTP);
    }

    private static TransferQuoteResponse aveniaQuote(String source, String destination) {
        return new TransferQuoteResponse(
                new MoneyAmount(new BigDecimal(source), "BRL"),
                new MoneyAmount(new BigDecimal(destination), "USDC"),
                new ExchangeRateInfo("USDC", "BRL", new BigDecimal("5.5")),
                new FeeInfo(BigDecimal.ZERO, BigDecimal.ZERO, "BRL"),
                new MoneyAmount(new BigDecimal(source), "BRL"));
    }

    /**
     * Gerenciador sem banco que, como o real, ativa a sincronização de transação e avisa
     * commit ou rollback; {@code failNextCommit} simula um commit de resultado desconhecido.
     */
    private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

        private boolean failNextCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Sem recurso transacional real.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new TransactionSystemException("commit outcome unknown");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Sem recurso transacional real.
        }
    }
}
