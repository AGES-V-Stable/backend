package ages.vstable.backend.dto.blindpay;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Respostas ao RFI aberto, por {@code key} do campo. Cada valor é um texto (até 4096
 * caracteres) ou uma lista de até 20 URLs https de documentos.
 */
public record BlindPayRfiSubmitRequest(
        @NotEmpty @Size(max = 400) Map<String, Object> responses
) {
}
