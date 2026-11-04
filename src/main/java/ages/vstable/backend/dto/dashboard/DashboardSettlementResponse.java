package ages.vstable.backend.dto.dashboard;

import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Transferência recente exibida no dashboard")
public record DashboardSettlementResponse(
        UUID id,
        UUID companyId,
        @Schema(description = "Nome fantasia ou razão social da empresa") String companyName,
        @Schema(description = "Momento da liquidação; nulo quando não registrado") OffsetDateTime settledAt,
        OffsetDateTime createdAt,
        TransferDirection direction,
        @Schema(description = "Beneficiário (pagamento) ou pagador externo (recebimento)") String counterparty,
        @Schema(description = "País e banco do beneficiário, quando registrados", example = "CN • Agricultural Bank of China")
        String destination,
        @Schema(description = "Valor na moeda estrangeira", example = "10000.00") String amount,
        @Schema(example = "USD") String currency,
        @Schema(example = "54200.00") String amountBrl,
        @Schema(example = "5.420000") String exchangeRate,
        @Schema(example = "120.00") String feeBrl,
        @Schema(description = "Economia estimada; nula quando não liquidada ou sem dados") String savings,
        TransactionStatus status) {
}
