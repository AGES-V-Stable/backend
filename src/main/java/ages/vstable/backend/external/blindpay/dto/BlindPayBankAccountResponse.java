package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/**
 * Recorte da conta bancária devolvida pela BlindPay. Os dados bancários completos
 * ficam no provedor: o backend só precisa do id, do trilho e do status de aprovação.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayBankAccountResponse(
        String id,
        String type,
        String name,
        String status,
        String beneficiaryName,
        String country,
        String recipientRelationship,
        OffsetDateTime createdAt
) {
}
