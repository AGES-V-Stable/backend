package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contrato monetário do adapter real; gateway/provisionamento inteiramente simulados. */
@Tag("security-assessment")
class FinancialBoundaryAssessmentTest {
    @ParameterizedTest(name="LC-OUT-04 cotacao incompleta {0}")
    @ValueSource(strings={"null-response","null-source","zero-source","negative-target"})
    void incompleteProviderAmountsAreRejectedWithControlledFailure(String condition) {
        when(provisioning.ensureBankAccount(company,beneficiary)).thenReturn("synthetic-bank");
        var response=condition.equals("null-response")?null:new BlindPayQuoteResponse("synthetic-quote",
                Instant.now().plusSeconds(300).toEpochMilli(),BigDecimal.ONE,BigDecimal.ONE,
                condition.equals("negative-target")?new BigDecimal("-9900"):new BigDecimal("9900"),
                condition.equals("null-source")?null:condition.equals("zero-source")?BigDecimal.ZERO:new BigDecimal("10000"),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,null);
        when(gateway.createQuote(any(),anyString())).thenReturn(response);
        assertThatThrownBy(()->provider.quote(context("USDB","base_sepolia","USDB","USD",QuoteAmountSide.SOURCE,"100"),"synthetic"))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
    }
    private final BlindPayGateway gateway = mock(BlindPayGateway.class);
    private final BlindPayProvisioningService provisioning = mock(BlindPayProvisioningService.class);
    private final BlindPayQuoteProvider provider = new BlindPayQuoteProvider(gateway, provisioning);
    private final CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
    private final BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
            .receivingMethod(ReceivingMethod.BANK_ACCOUNT).currency("USD").paymentRail("wire").build();

    @ParameterizedTest(name="TC-FIN-16 centavos {0} {1} {2}")
    @CsvSource({"USDC,SOURCE,5.00", "USDT,SOURCE,5.01", "USDB,SOURCE,100.12",
            "USDC,TARGET,5.00", "USDT,TARGET,5.01", "USDB,TARGET,100.12"})
    void exactMinorUnitsAndFeeSide(String token, QuoteAmountSide side, String amount) {
        arrangeQuote(new BigDecimal("123"));
        QuoteContext context = context(token, "base_sepolia", token, "USD", side, amount);
        assertThat(provider.supports(context)).isTrue();
        ProviderQuoteResult result = provider.quote(context, "synthetic-idempotency");
        verify(gateway).createQuote(argThat(request -> request.requestAmount().equals(new BigDecimal(amount).movePointRight(2).longValueExact())
                && request.coverFees() == (side == QuoteAmountSide.TARGET)), eq("synthetic-idempotency"));
        assertThat(result.totalFee()).isEqualByComparingTo("1.23");
        assertThat(result.exchangeRate()).isEqualByComparingTo("0.9900000000");
    }

    @ParameterizedTest(name="TC-FIN-17 precisao ou limite {0}")
    @ValueSource(strings={"5.001", "5.009", "92233720368547758.08", "999999999999999999999999"})
    void precisionAndOverflowCannotBeSilentlyRounded(String amount) {
        when(provisioning.ensureBankAccount(company, beneficiary)).thenReturn("synthetic-bank");
        assertThatThrownBy(() -> provider.quote(context("USDB", "base_sepolia", "USDB", "USD", QuoteAmountSide.SOURCE, amount), "synthetic"))
                .isInstanceOfAny(IllegalArgumentException.class,
                        ages.vstable.backend.exception.UnprocessableEntityException.class);
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest(name="TC-FIN-18 rota invalida {0} {1} {2} {3}")
    @CsvSource({"BTC,base_sepolia,BTC,USD", "USDB,unknown,USDB,USD", "USDB,base_sepolia,XYZ,USD",
            "USDB,base_sepolia,USDB,EUR", "EURC,base_sepolia,EURC,USD"})
    void unsupportedAssetNetworkOrBeneficiaryCurrency(String token, String network, String source, String target) {
        assertThat(provider.supports(context(token, network, source, target, QuoteAmountSide.SOURCE, "100"))).isFalse();
        verifyNoInteractions(gateway, provisioning);
    }

    @ParameterizedTest(name="TC-FIN-19 tarifa {0}")
    @ValueSource(strings={"0", "1", "123", "-1"})
    void providerFeeMustNotBeNegative(String minorUnits) {
        arrangeQuote(new BigDecimal(minorUnits));
        var context = context("USDB", "base_sepolia", "USDB", "USD", QuoteAmountSide.SOURCE, "100");
        if (new BigDecimal(minorUnits).signum() < 0) {
            assertThatThrownBy(() -> provider.quote(context, "synthetic"))
                    .as("Tarifa negativa de resposta externa deve ser rejeitada pelo adapter")
                    .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
        } else {
            assertThat(provider.quote(context, "synthetic").totalFee()).isEqualByComparingTo(new BigDecimal(minorUnits).movePointLeft(2));
        }
    }

    private void arrangeQuote(BigDecimal fee) {
        when(provisioning.ensureBankAccount(company, beneficiary)).thenReturn("synthetic-bank");
        when(gateway.createQuote(any(), anyString())).thenReturn(new BlindPayQuoteResponse("synthetic-quote",
                Instant.now().plusSeconds(300).toEpochMilli(), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("9900"), new BigDecimal("10000"), BigDecimal.ZERO, fee, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null));
    }

    private QuoteContext context(String token, String network, String source, String target, QuoteAmountSide side, String amount) {
        return new QuoteContext(UUID.randomUUID(), company, beneficiary, new QuoteRequest(beneficiary.getId(),
                QuoteDirection.PAYOUT, source, target, "wallet", "wire", new BigDecimal(amount), side,
                token, network, false, "Dados sinteticos"), UUID.randomUUID());
    }
}
