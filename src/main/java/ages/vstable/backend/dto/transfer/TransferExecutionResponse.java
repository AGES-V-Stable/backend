package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransferExecutionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Estado de uma transferência confirmada. {@code fundingInstructions} traz o código PIX a
 * pagar enquanto o status for AWAITING_FUNDS; {@code requote} traz a nova cotação que
 * precisa ser confirmada quando o status for REQUOTE_REQUIRED.
 */
public record TransferExecutionResponse(
        UUID executionId,
        UUID offerId,
        UUID transactionId,
        String provider,
        TransferExecutionStatus status,
        MoneyAmount source,
        MoneyAmount target,
        BigDecimal totalFee,
        String fundingInstructions,
        Requote requote,
        String failureCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public record Requote(MoneyAmount source, MoneyAmount target, OffsetDateTime expiresAt) {
    }
}
