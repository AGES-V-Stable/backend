package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Payout devolvido na criação (POST /payouts/evm) e na consulta (GET /payouts/{id}).
 * A criação traz só parte dos campos; os ausentes ficam nulos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayPayoutResponse(
        String id,
        String status,
        String customerId,
        String bankAccountId,
        String quoteId,
        String senderWalletAddress,
        String network,
        String token,
        String currency,
        BigDecimal senderAmount,
        BigDecimal receiverAmount,
        BigDecimal totalFeeAmount,
        BigDecimal billingFeeAmount,
        BigDecimal transactionFeeAmount,
        BlindPayTracking trackingTransaction,
        BlindPayTracking trackingPayment,
        BlindPayTracking trackingComplete,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
