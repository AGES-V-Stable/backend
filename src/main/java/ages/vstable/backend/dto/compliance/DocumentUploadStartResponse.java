package ages.vstable.backend.dto.compliance;

import lombok.Data;

@Data
public class DocumentUploadStartResponse {
    private String id;
    private String uploadUrlFront;
    private String uploadUrlBack;
}
