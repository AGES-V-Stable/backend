package ages.vstable.backend.external.avenia.dto;

import java.math.BigDecimal;

public record AveniaQuoteResult(
        BigDecimal inputAmount,
        BigDecimal outputAmount,
        String quoteToken,
        BigDecimal basePrice,
        String pairName,
        BigDecimal markupAmount,
        BigDecimal markupFloatingFee,
        String markupCurrency
) {}
