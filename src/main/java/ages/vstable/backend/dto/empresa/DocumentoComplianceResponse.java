package ages.vstable.backend.dto.empresa;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class DocumentoComplianceResponse {

    private UUID id;
    private TipoDocumento tipoDocumento;
    private String nomeArquivo;
    private Long tamanhoArquivoBytes;
    private StatusCompliance status;
    private OffsetDateTime enviadoEm;
}
