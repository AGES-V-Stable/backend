package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Recorte de GET /v1/instances/{instance_id}/customers/{customer_id}/rfi: o pedido
 * de informações aberto. A BlindPay responde {@code null} quando não há RFI pendente.
 * As respostas já enviadas ({@code response}) ficam de fora de propósito.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayRfiResponse(
        String id,
        String customerId,
        String instanceId,
        String status,
        List<Section> request,
        OffsetDateTime expiresAt,
        OffsetDateTime submittedAt,
        OffsetDateTime createdAt
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Section(
            String title,
            String description,
            String supportingDocument,
            List<Field> fields
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(
            String key,
            String label,
            String regex,
            Boolean required,
            List<Item> items,
            Boolean multiple,
            String aipriseDocumentType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String label, String value) {
    }
}
