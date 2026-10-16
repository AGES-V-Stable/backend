package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Resposta de POST .../customers/{customer_id}/rfi. {@code success=false} vem com
 * {@code reason} ({@code no_pending_rfi} ou {@code already_resolved}): o estado
 * deve ser consultado de novo em vez de repetir o envio.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlindPayRfiSubmitResponse(Boolean success, String reason) {

    public boolean succeeded() {
        return Boolean.TRUE.equals(success);
    }
}
