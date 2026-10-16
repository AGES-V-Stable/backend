package ages.vstable.backend.dto.blindpay;

import java.time.OffsetDateTime;
import java.util.List;

/** Pedido de informações (RFI) aberto pela BlindPay; {@code open=false} quando não há pendência. */
public record BlindPayRfiDetailsResponse(
        boolean open,
        String rfiId,
        OffsetDateTime expiresAt,
        List<Section> sections
) {

    public BlindPayRfiDetailsResponse {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public static BlindPayRfiDetailsResponse none() {
        return new BlindPayRfiDetailsResponse(false, null, null, List.of());
    }

    public record Section(String title, String description, String supportingDocument, List<Field> fields) {
    }

    /**
     * Campo pedido. {@code options} limita os valores aceitos; {@code multiple} indica
     * que a resposta é uma lista (por exemplo, várias URLs de documentos).
     */
    public record Field(
            String key,
            String label,
            boolean required,
            boolean multiple,
            String documentType,
            List<Option> options
    ) {
    }

    public record Option(String label, String value) {
    }
}
