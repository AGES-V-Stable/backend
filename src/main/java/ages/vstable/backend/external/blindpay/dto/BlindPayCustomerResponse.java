package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Recorte de GET /v1/instances/{instance_id}/customers/{id} com o que o backend
 * precisa para acompanhar o onboarding. Status chegam como texto para que um valor
 * novo da BlindPay não quebre a desserialização (ver {@code BlindPayApi.Customer}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayCustomerResponse(
        String id,
        String type,
        String kycType,
        String kycStatus,
        String amlStatus,
        String email,
        String country,
        String firstName,
        String lastName,
        String legalName,
        String externalId,
        String tosId,
        Boolean isTosAccepted,
        Limit limit,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** Limites em centavos de USD. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Limit(
            BigDecimal perTransaction,
            BigDecimal daily,
            BigDecimal monthly
    ) {
    }
}
