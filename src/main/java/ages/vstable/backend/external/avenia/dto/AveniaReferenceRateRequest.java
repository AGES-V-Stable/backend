package ages.vstable.backend.external.avenia.dto;

import java.math.BigDecimal;

/** Only the configured route and reference amount; no financial account or execution options. */
public record AveniaReferenceRateRequest(
        String inputCurrency,
        String inputPaymentMethod,
        String outputCurrency,
        String outputPaymentMethod,
        BigDecimal inputAmount
) {
    public AveniaReferenceRateRequest {
        requireText(inputCurrency, "inputCurrency");
        requireText(inputPaymentMethod, "inputPaymentMethod");
        requireText(outputCurrency, "outputCurrency");
        requireText(outputPaymentMethod, "outputPaymentMethod");
        if (inputAmount == null || inputAmount.signum() <= 0) {
            throw new IllegalArgumentException("inputAmount must be greater than zero");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
    }
}
