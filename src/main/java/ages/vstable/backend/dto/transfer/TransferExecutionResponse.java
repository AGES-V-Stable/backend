package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Estado de uma transferência confirmada. Enquanto o status for AWAITING_FUNDS, as instruções
 * de pagamento seguem {@code fundingMethod}: PIX em {@code fundingInstructions} (copia e cola)
 * e TED em {@code fundingBankTransfer}. {@code requote} traz a nova cotação que precisa ser
 * confirmada quando o status for REQUOTE_REQUIRED.
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
        TransferMethod fundingMethod,
        BankTransfer fundingBankTransfer,
        Requote requote,
        String failureCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public record Requote(MoneyAmount source, MoneyAmount target, OffsetDateTime expiresAt) {
    }

    /**
     * Dados para pagar por TED, como a BlindPay os devolve no payin ({@code memo_code} e
     * {@code blindpay_bank_details}). Campos ausentes ficam nulos: nada é presumido.
     */
    public record BankTransfer(
            String memoCode,
            String beneficiaryName,
            String beneficiaryAddress,
            String bankName,
            String bankAddress,
            String routingNumber,
            String accountNumber,
            String accountType
    ) {
    }
}
