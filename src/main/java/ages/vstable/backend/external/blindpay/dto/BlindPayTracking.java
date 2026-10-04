package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Etapa de acompanhamento ({@code tracking_*}) de payouts e payins. Cada etapa
 * preenche só os campos que fazem sentido para ela; os demais chegam nulos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayTracking(
        String step,
        String status,
        String providerStatus,
        String transactionHash,
        String estimatedTimeOfArrival,
        String completedAt
) {
}
