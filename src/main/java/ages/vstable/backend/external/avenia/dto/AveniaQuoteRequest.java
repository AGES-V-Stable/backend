package ages.vstable.backend.external.avenia.dto;

import java.math.BigDecimal;

public record AveniaQuoteRequest(
        String inputCurrency,
        String inputPaymentMethod,
        String outputCurrency,
        String outputPaymentMethod,
        BigDecimal inputAmount,
        BigDecimal outputAmount,
        Boolean inputThirdParty,
        Boolean outputThirdParty,
        String blockchainSendMethod,
        BigDecimal markupFloatingFee,
        BigDecimal markupInputFixedFee,
        BigDecimal markupOutputFixedFee,
        String markupCurrency,
        String ticketRefundId,
        String outputBrCode,
        String subAccountId
) {

    public AveniaQuoteRequest {
        requireText(inputCurrency, "inputCurrency");
        requireText(inputPaymentMethod, "inputPaymentMethod");
        requireText(outputCurrency, "outputCurrency");
        requireText(outputPaymentMethod, "outputPaymentMethod");

        if ((inputAmount == null) == (outputAmount == null)) {
            throw new IllegalArgumentException("Exactly one of inputAmount or outputAmount must be provided");
        }
        if (inputAmount != null && inputAmount.signum() <= 0) {
            throw new IllegalArgumentException("inputAmount must be greater than zero");
        }
        if (outputAmount != null && outputAmount.signum() <= 0) {
            throw new IllegalArgumentException("outputAmount must be greater than zero");
        }

        inputThirdParty = inputThirdParty == null ? Boolean.FALSE : inputThirdParty;
        outputThirdParty = outputThirdParty == null ? Boolean.FALSE : outputThirdParty;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be provided");
        }
    }
}
