package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/** Cotação de payin. Valores em centavos; {@code expiresAt} é epoch em milissegundos. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayPayinQuoteResponse(
        String id,
        Long expiresAt,
        BigDecimal commercialQuotation,
        BigDecimal blindpayQuotation,
        BigDecimal receiverAmount,
        BigDecimal senderAmount,
        BigDecimal partnerFeeAmount,
        BigDecimal flatFee,
        BigDecimal billingFeeAmount
) {
}
