package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AveniaQuoteProviderTest {

    @Mock private TransferService transferService;

    private AveniaQuoteProvider provider;
    private CompanyEntity company;
    private BeneficiaryEntity wallet;
    private UUID userId;

    @BeforeEach
    void setUp() {
        provider = new AveniaQuoteProvider(transferService);
        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        wallet = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .receivingMethod(ReceivingMethod.CRYPTO_WALLET).build();
        userId = UUID.randomUUID();
    }

    @Test
    void supports_brlPixToCryptoWallet_isTheExecutableAveniaRoute() {
        assertThat(provider.supports(context(wallet, "BRL", "PIX"))).isTrue();
    }

    @Test
    void supports_routesWithoutAveniaExecution_areNotQuoted() {
        BeneficiaryEntity bankAccount = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT).build();

        assertThat(provider.supports(context(bankAccount, "BRL", "PIX"))).isFalse();
        assertThat(provider.supports(context(wallet, "USDC", "BLOCKCHAIN"))).isFalse();
        assertThat(provider.supports(context(wallet, "BRL", "TED"))).isFalse();
    }

    @Test
    void quote_usesSameRouteAndSubAccountContextAsTransferExecution() {
        // Arrange
        when(transferService.quote(any(), eq(userId), eq(company.getId()))).thenReturn(new TransferQuoteResponse(
                new MoneyAmount(new BigDecimal("1000.00"), "BRL"),
                new MoneyAmount(new BigDecimal("180.50"), "USDC"),
                new ExchangeRateInfo("USDC", "BRL", new BigDecimal("5.50")),
                new FeeInfo(new BigDecimal("0.50"), new BigDecimal("5.00"), "BRL"),
                new MoneyAmount(new BigDecimal("1000.00"), "BRL")));

        // Act
        ProviderQuoteResult result = provider.quote(context(wallet, "BRL", "PIX"), "key");

        // Assert
        ArgumentCaptor<TransferQuoteRequest> request = ArgumentCaptor.forClass(TransferQuoteRequest.class);
        verify(transferService).quote(request.capture(), eq(userId), eq(company.getId()));
        assertThat(request.getValue().getPaymentMethod()).isEqualTo(TransferMethod.PIX);
        assertThat(request.getValue().getAmountType()).isEqualTo(TransferAmountType.SOURCE);
        assertThat(request.getValue().getBeneficiaryId()).isEqualTo(wallet.getId());
        assertThat(result.provider()).isEqualTo(IntegrationProvider.AVENIA);
        assertThat(result.sourceAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.targetAmount()).isEqualByComparingTo("180.50");
        assertThat(result.totalFee()).isEqualByComparingTo("5.00");
        assertThat(result.expiresAt()).isAfter(OffsetDateTime.now());
    }

    private QuoteContext context(BeneficiaryEntity beneficiary, String sourceCurrency, String sourceMethod) {
        QuoteRequest request = new QuoteRequest(beneficiary.getId(), QuoteDirection.PAYOUT, sourceCurrency, "USDC",
                sourceMethod, "POLYGON", new BigDecimal("1000.00"), QuoteAmountSide.SOURCE, null, null, false, null);
        return new QuoteContext(UUID.randomUUID(), company, beneficiary, request, userId);
    }
}
