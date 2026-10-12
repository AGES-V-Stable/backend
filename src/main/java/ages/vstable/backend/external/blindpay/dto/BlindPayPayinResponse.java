package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Payin devolvido na criação (POST /payins/evm) e na consulta (GET /payins/{id}).
 * {@code pixCode}, {@code clabe}, {@code memoCode} e {@code blindpayBankDetails} são
 * as instruções que o pagador usa para enviar o dinheiro, conforme o método escolhido.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayPayinResponse(
        String id,
        String status,
        String customerId,
        String paymentMethod,
        String pixCode,
        String memoCode,
        String clabe,
        JsonNode blindpayBankDetails,
        String currency,
        String token,
        BigDecimal senderAmount,
        BigDecimal receiverAmount,
        BigDecimal billingFeeAmount,
        BigDecimal transactionFeeAmount,
        BlindPayTracking trackingTransaction,
        BlindPayTracking trackingPayment,
        BlindPayTracking trackingComplete,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
