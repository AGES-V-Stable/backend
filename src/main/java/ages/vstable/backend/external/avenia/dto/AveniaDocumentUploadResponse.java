package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

// Shape real confirmado em 2026-09-21 contra o sandbox: POST /v2/documents/ com
// documentType ID retorna "uploadURLFront"/"uploadURLBack" (URL em maiúsculas),
// não "uploadUrlFront"/"uploadUrlBack" — sem o @JsonProperty esses campos vinham
// sempre null.
@Data
public class AveniaDocumentUploadResponse {
    private String id;
    @JsonProperty("uploadURLFront")
    private String uploadUrlFront;
    @JsonProperty("uploadURLBack")
    private String uploadUrlBack;
}
