package ages.vstable.backend.dto.transaction;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Volume transacionado da empresa autenticada")
public record TransactionVolumeResponse(
        @Schema(description = "Soma em BRL das transações liquidadas, string decimal com duas casas", example = "412000.00")
        String transactedVolumeBrl) {
}
