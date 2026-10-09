package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Envelope de erro da BlindPay. Só os campos seguros são mapeados: {@code code}
 * (estável), {@code description} (texto pensado para o usuário final) e
 * {@code traceId} (para suporte). {@code message} e {@code errors} podem ecoar
 * dados enviados e ficam de fora de propósito.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayErrorResponse(
        String code,
        String description,
        String traceId
) {
}
