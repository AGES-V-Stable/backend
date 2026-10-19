package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/**
 * Corpo de POST /v1/e/instances/{instance_id}/tos (aceite hospedado). A
 * {@code idempotencyKey} volta no webhook {@code tos.accept} e correlaciona o
 * aceite com a empresa; {@code customerId} só existe quando o cliente já foi criado.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayInitiateTosRequest(
        UUID idempotencyKey,
        String customerId,
        String redirectUrl
) {

    public BlindPayInitiateTosRequest {
        RequestValidation.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
