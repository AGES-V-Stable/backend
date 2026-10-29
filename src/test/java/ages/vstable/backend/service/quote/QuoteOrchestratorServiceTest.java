package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.logging.LogCapture;
import ages.vstable.backend.logging.LogContext;
import ages.vstable.backend.logging.OperationalLog;
import ages.vstable.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuoteOrchestratorServiceTest {

    @Mock private QuoteProvider avenia;
    @Mock private QuoteProvider blindPay;
    @Mock private CompanyRepository companyRepository;
    @Mock private BeneficiaryRepository beneficiaryRepository;
    @Mock private QuoteRequestRepository quoteRequestRepository;
    @Mock private ProviderQuoteRepository providerQuoteRepository;

    private QuoteOrchestratorService service;
    private UserEntity user;
    private BeneficiaryEntity beneficiary;

    @BeforeEach
    void setUp() {
        service = new QuoteOrchestratorService(
                List.of(avenia, blindPay), companyRepository, beneficiaryRepository,
                quoteRequestRepository, providerQuoteRepository, Runnable::run);
        ReflectionTestUtils.setField(service, "providerTimeout", Duration.ofSeconds(1));

        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).legalName("Empresa").cnpj("1")
                .country("Brasil").zipCode("1").state("RS").build();
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).email("user@example.com").build();
        beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").accountHolderName("Fornecedor Ltda")
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT).build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), companyId))
                .thenReturn(Optional.of(beneficiary));
        when(quoteRequestRepository.save(any())).thenAnswer(invocation -> {
            QuoteRequestEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            return entity;
        });
        lenient().when(providerQuoteRepository.save(any())).thenAnswer(invocation -> {
            ProviderQuoteEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    @Test
    void quote_oneProviderFails_returnsAvailableOfferAndPersistsBothResults() {
        when(avenia.provider()).thenReturn(IntegrationProvider.AVENIA);
        when(blindPay.provider()).thenReturn(IntegrationProvider.BLINDPAY);
        when(avenia.supports(any())).thenReturn(true);
        when(blindPay.supports(any())).thenReturn(true);
        when(avenia.quote(any(), any())).thenReturn(new ProviderQuoteResult(
                IntegrationProvider.AVENIA, "external-1", new BigDecimal("100.00"),
                new BigDecimal("19.00"), new BigDecimal("5.00"), BigDecimal.ONE,
                BigDecimal.ONE, OffsetDateTime.now().plusSeconds(15), "{}"));
        when(blindPay.quote(any(), any())).thenThrow(
                BlindPayIntegrationException.invalidResponse("BlindPay sender amount must be greater than zero"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("19.00");
        verify(providerQuoteRepository, times(2)).save(any());
        verify(quoteRequestRepository, times(2)).save(any());
    }

    @Test
    void quote_blindPayComplianceNotApproved_returnsAveniaOfferAndRecordsComplianceFailure() {
        when(avenia.provider()).thenReturn(IntegrationProvider.AVENIA);
        when(blindPay.provider()).thenReturn(IntegrationProvider.BLINDPAY);
        when(avenia.supports(any())).thenReturn(true);
        when(blindPay.supports(any())).thenReturn(true);
        when(avenia.quote(any(), any())).thenReturn(result(
                IntegrationProvider.AVENIA, "100.00", "19.00", "1.00"));
        when(blindPay.quote(any(), any())).thenThrow(new BlindPayComplianceException(
                "BlindPay compliance is not approved for this company"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("19.00");
        ArgumentCaptor<ProviderQuoteEntity> saved = ArgumentCaptor.forClass(ProviderQuoteEntity.class);
        verify(providerQuoteRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(quote -> quote.getProvider() == IntegrationProvider.BLINDPAY)
                .singleElement()
                .satisfies(quote -> {
                    assertThat(quote.getStatus()).isEqualTo("FAILED");
                    assertThat(quote.getFailureCode()).isEqualTo("BlindPayComplianceException");
                });
    }

    @Test
    void quote_aveniaFails_returnsBlindPayOfferAndPersistsBothResults() {
        when(avenia.provider()).thenReturn(IntegrationProvider.AVENIA);
        when(blindPay.provider()).thenReturn(IntegrationProvider.BLINDPAY);
        when(avenia.supports(any())).thenReturn(true);
        when(blindPay.supports(any())).thenReturn(true);
        when(avenia.quote(any(), any())).thenThrow(new RuntimeException("provider unavailable"));
        when(blindPay.quote(any(), any())).thenReturn(result(
                IntegrationProvider.BLINDPAY, "100.00", "19.00", "1.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("19.00");
        verify(providerQuoteRepository, times(2)).save(any());
        verify(quoteRequestRepository, times(2)).save(any());
    }

    @Test
    void quote_providerDoesNotSupportRequest_returnsUnprocessableEntity() {
        when(avenia.supports(any())).thenReturn(false);
        when(blindPay.supports(any())).thenReturn(false);

        assertThatThrownBy(() -> service.quote(user, request()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("No quote is available for the requested operation");
        verify(avenia, never()).quote(any(), any());
        verify(blindPay, never()).quote(any(), any());
    }

    @Test
    void quote_sourceAmountFixed_returnsOfferWithHighestTargetAmount() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "100.00", "18.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "19.00", "2.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("19.00");
    }

    @Test
    void quote_targetAmountFixed_returnsOfferWithLowestSourceAmount() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "102.00", "100.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "100.00", "2.00"));

        QuoteResponse response = service.quote(user, request(QuoteAmountSide.TARGET));

        assertThat(response.offer().sourceAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void quote_sameAmounts_doesNotUseFeesWithoutComparableCurrency() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "100.00", "20.00", "2.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "20.00", "1.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().totalFee()).isEqualByComparingTo("2.00");
    }

    @Test
    void quote_offerThatChangesFixedSourceAmount_isNotSelected() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "101.00", "25.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "20.00", "1.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().sourceAmount()).isEqualByComparingTo("100.00");
        assertThat(response.offer().targetAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void quote_offerThatChangesFixedTargetAmount_isNotSelected() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "90.00", "19.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "100.00", "1.00"));

        QuoteResponse response = service.quote(user, request(QuoteAmountSide.TARGET));

        assertThat(response.offer().sourceAmount()).isEqualByComparingTo("100.00");
        assertThat(response.offer().targetAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void quote_expiredOffer_doesNotSelectIt() {
        ProviderQuoteResult expired = new ProviderQuoteResult(
                IntegrationProvider.AVENIA,
                "expired",
                new BigDecimal("100.00"),
                new BigDecimal("25.00"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                OffsetDateTime.now().minusSeconds(1),
                "{}");
        configureSuccessfulProviders(
                expired,
                result(IntegrationProvider.BLINDPAY, "100.00", "20.00", "1.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void quote_persistsOnlyTheWinningOfferAsConfirmable() {
        // Arrange
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "100.00", "18.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "19.00", "2.00"));

        // Act
        QuoteResponse response = service.quote(user, request());

        // Assert
        ArgumentCaptor<ProviderQuoteEntity> saved = ArgumentCaptor.forClass(ProviderQuoteEntity.class);
        verify(providerQuoteRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(ProviderQuoteEntity::getProvider, ProviderQuoteEntity::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(IntegrationProvider.AVENIA, ProviderQuoteStatus.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple(IntegrationProvider.BLINDPAY, ProviderQuoteStatus.SELECTED));
        assertThat(response.offer().provider()).isEqualTo("BLINDPAY");
        assertThat(response.offer().offerId()).isEqualTo(saved.getAllValues().stream()
                .filter(quote -> quote.getProvider() == IntegrationProvider.BLINDPAY)
                .findFirst().orElseThrow().getId());
    }

    @Test
    void quote_offerWithoutExpiration_isNotSelected() {
        ProviderQuoteResult withoutExpiration = new ProviderQuoteResult(
                IntegrationProvider.AVENIA, "no-expiry", new BigDecimal("100.00"), new BigDecimal("25.00"),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, "{}");
        configureSuccessfulProviders(
                withoutExpiration,
                result(IntegrationProvider.BLINDPAY, "100.00", "20.00", "1.00"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("20.00");
        assertThat(response.offer().provider()).isEqualTo("BLINDPAY");
    }

    @Test
    void quote_passesRequestingUserToProviders() {
        configureSuccessfulProviders(
                result(IntegrationProvider.AVENIA, "100.00", "18.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "19.00", "2.00"));

        service.quote(user, request());

        ArgumentCaptor<QuoteContext> context = ArgumentCaptor.forClass(QuoteContext.class);
        verify(avenia).quote(context.capture(), any());
        assertThat(context.getValue().requestedBy()).isEqualTo(user.getId());
    }

    private void configureSuccessfulProviders(ProviderQuoteResult aveniaResult, ProviderQuoteResult blindPayResult) {
        when(avenia.provider()).thenReturn(IntegrationProvider.AVENIA);
        when(blindPay.provider()).thenReturn(IntegrationProvider.BLINDPAY);
        when(avenia.supports(any())).thenReturn(true);
        when(blindPay.supports(any())).thenReturn(true);
        when(avenia.quote(any(), any())).thenReturn(aveniaResult);
        when(blindPay.quote(any(), any())).thenReturn(blindPayResult);
    }

    private ProviderQuoteResult result(
            IntegrationProvider provider, String sourceAmount, String targetAmount, String fee) {
        return new ProviderQuoteResult(
                provider,
                "external-" + provider.name().toLowerCase(),
                new BigDecimal(sourceAmount),
                new BigDecimal(targetAmount),
                BigDecimal.ONE,
                new BigDecimal(fee),
                new BigDecimal(fee),
                OffsetDateTime.now().plusSeconds(15),
                "{}");
    }

    private QuoteRequest request() {
        return request(QuoteAmountSide.SOURCE);
    }

    private QuoteRequest request(QuoteAmountSide amountSide) {
        return new QuoteRequest(
                beneficiary.getId(), QuoteDirection.PAYOUT, "USDC", "USD", "BLOCKCHAIN", "SWIFT",
                new BigDecimal("100.00"), amountSide, "USDC", "base", false, "Invoice");
    }
    @Test
    void quotePartialFailureLogsOnlySafeAggregationMetadata() {
        configureSuccessfulProviders(result(IntegrationProvider.AVENIA, "100.00", "19.00", "1.00"),
                result(IntegrationProvider.BLINDPAY, "100.00", "18.00", "1.00"));
        when(blindPay.quote(any(), any())).thenThrow(new IllegalStateException("synthetic-private-payload"));
        try (var capture = new LogCapture(
                OperationalLog.class)) {
            service.quote(user, request());
            assertThat(capture.events()).hasSize(1);
            var fields = LogCapture.fields(capture.events().getFirst());
            assertThat(fields).containsEntry("event", "quote.aggregation.completed")
                    .containsEntry("outcome", "partial").containsEntry("timeoutCount", 0L);
            assertThat(fields.toString()).doesNotContain("synthetic-private-payload", "Invoice", "external-");
        }
    }

    @Test
    void orchestrationTimeoutIsDistinctFromCorrelatedLateProviderCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        when(avenia.supports(any())).thenReturn(true);
        when(blindPay.supports(any())).thenReturn(false);
        when(avenia.provider()).thenReturn(IntegrationProvider.AVENIA);
        when(avenia.quote(any(), any())).thenAnswer(invocation -> {
            long startedAt = System.nanoTime();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            OperationalLog.provider("avenia", "quote.fetch", "success", 200, startedAt);
            finished.countDown();
            return result(IntegrationProvider.AVENIA, "100.00", "19.00", "1.00");
        });
        Executor executor = command -> {
            worker.submit(new LogContext().decorate(command));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        };
        service = new QuoteOrchestratorService(List.of(avenia, blindPay), companyRepository,
                beneficiaryRepository, quoteRequestRepository, providerQuoteRepository, executor);
        ReflectionTestUtils.setField(service, "providerTimeout", Duration.ZERO);
        MDC.put("requestId", "timed-out-request");
        try (var capture = new LogCapture(
                OperationalLog.class)) {
            assertThatThrownBy(() -> service.quote(user, request()))
                    .isInstanceOf(UnprocessableEntityException.class);
            assertThat(capture.events()).hasSize(1);
            assertThat(LogCapture.fields(capture.events().getFirst()))
                    .containsEntry("event", "quote.aggregation.completed").containsEntry("timeoutCount", 1L)
                    .containsEntry("outcome", "failure");
            MDC.clear();
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capture.events()).hasSize(2);
            var late = capture.events().getLast();
            assertThat(LogCapture.fields(late))
                    .containsEntry("event", "provider.call.completed").containsEntry("outcome", "success");
            assertThat(late.getMDCPropertyMap()).containsEntry("requestId", "timed-out-request");
        } finally {
            release.countDown();
            worker.shutdownNow();
            MDC.clear();
        }
    }

}
