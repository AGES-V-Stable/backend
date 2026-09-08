package ages.vstable.backend.dto.documento;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentoComplianceResponse(
        UUID id,
        UUID empresaId,
        TipoDocumento tipoDocumento,
        String nomeArquivo,
        String urlArquivo,
        long tamanhoArquivoBytes,
        StatusCompliance status,
        OffsetDateTime enviadoEm
) {
}
