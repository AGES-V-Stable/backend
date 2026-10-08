package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlindPayQuoteProviderTest {

    @Mock private BlindPayGateway blindPayGateway;
    @Mock private BlindPayProvisioningService provisioningService;

    private BlindPayQuoteProvider provider;
    private CompanyEntity company;
    private BeneficiaryEntity beneficiary;

    @BeforeEach
    void setUp() {
        provider = new BlindPayQuoteProvider(blindPayGateway, provisioningService);
        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        beneficiary = BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .currency("USD")
                .build();
    }

    @Test
    void supports_brlPayoutToBankAccount_returnsTrue() {
        assertThat(provider.supports(context(QuoteAmountSide.SOURCE, new BigDecimal("1000.00"))))
                .isTrue();
    }

    @Test
    void quote_brlSourceAmount_composesBlindPayPayinAndPayout() {
        when(provisioningService.ensureBankAccount(company, beneficiary)).thenReturn("bank-account-id");
        when(provisioningService.ensureWallet(company, "polygon")).thenReturn("wallet-id");
        when(blindPayGateway.createPayinQuote(any(), anyString())).thenReturn(blindPayPayinQuote(
                new BigDecimal("100000"), new BigDecimal("18012")));
        when(blindPayGateway.createQuote(any(), anyString())).thenReturn(blindPayQuote(
                new BigDecimal("18012"), new BigDecimal("99000")));

        ProviderQuoteResult result = provider.quote(
                context(QuoteAmountSide.SOURCE, new BigDecimal("1000.00")), "idempotency-key");

        ArgumentCaptor<BlindPayPayinQuoteRequest> fundingRequest =
                ArgumentCaptor.forClass(BlindPayPayinQuoteRequest.class);
        verify(blindPayGateway).createPayinQuote(fundingRequest.capture(), anyString());
        assertThat(fundingRequest.getValue().walletId()).isEqualTo("wallet-id");
        assertThat(fundingRequest.getValue().paymentMethod()).isEqualTo(BlindPayApi.Payin.PaymentMethod.pix);
        assertThat(fundingRequest.getValue().currencyType()).isEqualTo(BlindPayApi.Quote.CurrencyType.sender);
        assertThat(fundingRequest.getValue().requestAmount()).isEqualTo(100000L);

        ArgumentCaptor<BlindPayQuoteRequest> payoutRequest = ArgumentCaptor.forClass(BlindPayQuoteRequest.class);
        verify(blindPayGateway).createQuote(payoutRequest.capture(), anyString());
        assertThat(payoutRequest.getValue().currencyType()).isEqualTo(BlindPayApi.Quote.CurrencyType.sender);
        assertThat(payoutRequest.getValue().requestAmount()).isEqualTo(18012L);
        assertThat(result.sourceAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.targetAmount()).isEqualByComparingTo("990.00");
        assertThat(result.metadata()).contains("fundingQuoteId", "blindpay-payin-quote", "blindpay-quote");
    }

    @Test
    void quote_brlTargetAmount_quotesPayoutBeforeFunding() {
        when(provisioningService.ensureBankAccount(company, beneficiary)).thenReturn("bank-account-id");
        when(provisioningService.ensureWallet(company, "polygon")).thenReturn("wallet-id");
        when(blindPayGateway.createQuote(any(), anyString())).thenReturn(blindPayQuote(
                new BigDecimal("18234"), new BigDecimal("100000")));
        when(blindPayGateway.createPayinQuote(any(), anyString())).thenReturn(blindPayPayinQuote(
                new BigDecimal("101000"), new BigDecimal("18234")));

        ProviderQuoteResult result = provider.quote(
                context(QuoteAmountSide.TARGET, new BigDecimal("1000.00")), "idempotency-key");

        ArgumentCaptor<BlindPayQuoteRequest> payoutRequest = ArgumentCaptor.forClass(BlindPayQuoteRequest.class);
        verify(blindPayGateway).createQuote(payoutRequest.capture(), anyString());
        assertThat(payoutRequest.getValue().currencyType()).isEqualTo(BlindPayApi.Quote.CurrencyType.receiver);
        assertThat(payoutRequest.getValue().requestAmount()).isEqualTo(100000L);

        ArgumentCaptor<BlindPayPayinQuoteRequest> fundingRequest =
                ArgumentCaptor.forClass(BlindPayPayinQuoteRequest.class);
        verify(blindPayGateway).createPayinQuote(fundingRequest.capture(), anyString());
        assertThat(fundingRequest.getValue().currencyType()).isEqualTo(BlindPayApi.Quote.CurrencyType.receiver);
        assertThat(fundingRequest.getValue().requestAmount()).isEqualTo(18234L);
        assertThat(result.sourceAmount()).isEqualByComparingTo("1010.00");
        assertThat(result.targetAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void quote_tokenSourceAmount_usesEffectiveRateFromAmountsInCurrencyUnits() {
        beneficiary.setCurrency("BRL");
        beneficiary.setPaymentRail("pix");
        when(provisioningService.ensureBankAccount(company, beneficiary)).thenReturn("bank-account-id");
        when(blindPayGateway.createQuote(any(), anyString())).thenReturn(blindPayQuote(
                new BigDecimal("1010"), new BigDecimal("5240")));

        QuoteRequest request = new QuoteRequest(
                beneficiary.getId(), QuoteDirection.PAYOUT,
                "USDC", "BRL", "BLOCKCHAIN", "pix", new BigDecimal("10.10"),
                QuoteAmountSide.SOURCE, "USDC", "polygon", false, "Invoice");
        QuoteContext quoteContext = new QuoteContext(UUID.randomUUID(), company, beneficiary, request);

        assertThat(provider.supports(quoteContext)).isTrue();
        ProviderQuoteResult result = provider.quote(quoteContext, "idempotency-key");

        assertThat(result.sourceAmount()).isEqualByComparingTo("10.10");
        assertThat(result.targetAmount()).isEqualByComparingTo("52.40");
        assertThat(result.exchangeRate()).isEqualByComparingTo("5.1881188119");
        ArgumentCaptor<BlindPayQuoteRequest> payoutRequest = ArgumentCaptor.forClass(BlindPayQuoteRequest.class);
        verify(blindPayGateway).createQuote(payoutRequest.capture(), anyString());
        assertThat(payoutRequest.getValue().requestAmount()).isEqualTo(1010L);
    }

    @Test
    void supports_requestWithDifferentDestinationRail_doesNotQuoteWrongBankRail() {
        QuoteRequest request = new QuoteRequest(
                beneficiary.getId(), QuoteDirection.PAYOUT,
                "BRL", "USD", "pix", "pix", new BigDecimal("1000.00"),
                QuoteAmountSide.SOURCE, "USDC", "polygon", false, "Invoice");

        assertThat(provider.supports(new QuoteContext(UUID.randomUUID(), company, beneficiary, request)))
                .isFalse();
    }

    @Test
    void quote_convertsBlindPayExpirationFromEpochMilliseconds() {
        beneficiary.setCurrency("BRL");
        beneficiary.setPaymentRail("pix");
        when(provisioningService.ensureBankAccount(company, beneficiary)).thenReturn("bank-account-id");
        Instant expiration = Instant.parse("2026-10-08T15:30:00Z");
        when(blindPayGateway.createQuote(any(), anyString())).thenReturn(blindPayQuote(
                new BigDecimal("10000"), new BigDecimal("52000"), expiration.toEpochMilli()));

        QuoteRequest request = new QuoteRequest(
                beneficiary.getId(), QuoteDirection.PAYOUT,
                "USDC", "BRL", "BLOCKCHAIN", "pix", new BigDecimal("100.00"),
                QuoteAmountSide.SOURCE, "USDC", "polygon", false, "Invoice");

        ProviderQuoteResult result = provider.quote(
                new QuoteContext(UUID.randomUUID(), company, beneficiary, request), "idempotency-key");

        assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.ofInstant(expiration, ZoneOffset.UTC));
    }

    private QuoteContext context(QuoteAmountSide amountSide, BigDecimal amount) {
        QuoteRequest request = new QuoteRequest(
                beneficiary.getId(),
                QuoteDirection.PAYOUT,
                "BRL",
                "USD",
                "PIX",
                "SWIFT",
                amount,
                amountSide,
                "USDC",
                "polygon",
                false,
                "Invoice");
        return new QuoteContext(UUID.randomUUID(), company, beneficiary, request);
    }

    private BlindPayPayinQuoteResponse blindPayPayinQuote(
            BigDecimal senderAmount, BigDecimal receiverAmount) {
        return new BlindPayPayinQuoteResponse(
                "blindpay-payin-quote",
                Instant.now().plusSeconds(50).toEpochMilli(),
                new BigDecimal("5.50"),
                new BigDecimal("5.55"),
                receiverAmount,
                senderAmount,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("50"));
    }

    private BlindPayQuoteResponse blindPayQuote(BigDecimal senderAmount, BigDecimal receiverAmount) {
        return blindPayQuote(senderAmount, receiverAmount, Instant.now().plusSeconds(60).toEpochMilli());
    }

    private BlindPayQuoteResponse blindPayQuote(
            BigDecimal senderAmount, BigDecimal receiverAmount, long expiresAt) {
        return new BlindPayQuoteResponse(
                "blindpay-quote",
                expiresAt,
                new BigDecimal("5.50"),
                new BigDecimal("5.55"),
                receiverAmount,
                senderAmount,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("50"),
                null,
                "Invoice",
                null);
    }
}
