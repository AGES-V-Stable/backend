package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

@Data
public class AveniaDocumentResponse {
    private String id;
    private String sessionId;
    private String livenessUrl;
    private String validateLivenessToken;
}
