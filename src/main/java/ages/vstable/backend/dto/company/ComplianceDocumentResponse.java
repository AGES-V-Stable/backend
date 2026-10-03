package ages.vstable.backend.dto.company;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.DocumentType;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class ComplianceDocumentResponse {

    private UUID id;
    private DocumentType documentType;
    private String fileName;
    private Long fileSizeBytes;
    private ComplianceStatus status;
    private OffsetDateTime uploadedAt;
}
