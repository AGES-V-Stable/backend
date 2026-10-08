package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        when(blindPay.quote(any(), any())).thenThrow(new RuntimeException("provider unavailable"));

        QuoteResponse response = service.quote(user, request());

        assertThat(response.offer().targetAmount()).isEqualByComparingTo("19.00");
        verify(providerQuoteRepository, times(2)).save(any());
        verify(quoteRequestRepository, times(2)).save(any());
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
                .isInstanceOf(ages.vstable.backend.exception.UnprocessableEntityException.class)
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
}
