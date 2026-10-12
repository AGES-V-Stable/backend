package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AveniaQuoteResponse(
        String quoteToken,
        String inputCurrency,
        String inputPaymentMethod,
        BigDecimal inputAmount,
        String outputCurrency,
        String outputPaymentMethod,
        BigDecimal outputAmount,
        BigDecimal markupAmount,
        String markupCurrency,
        Boolean inputThirdParty,
        Boolean outputThirdParty,
        List<AveniaAppliedFee> appliedFees,
        BigDecimal basePrice,
        String pairName,
        String feeSettlement
) {

    public AveniaQuoteResponse(
            String quoteToken,
            String inputCurrency,
            String inputPaymentMethod,
            BigDecimal inputAmount,
            String outputCurrency,
            String outputPaymentMethod,
            BigDecimal outputAmount,
            BigDecimal markupAmount,
            String markupCurrency,
            Boolean inputThirdParty,
            Boolean outputThirdParty,
            List<AveniaAppliedFee> appliedFees,
            BigDecimal basePrice,
            String pairName
    ) {
        this(quoteToken, inputCurrency, inputPaymentMethod, inputAmount,
                outputCurrency, outputPaymentMethod, outputAmount, markupAmount,
                markupCurrency, inputThirdParty, outputThirdParty, appliedFees,
                basePrice, pairName, null);
    }
}
