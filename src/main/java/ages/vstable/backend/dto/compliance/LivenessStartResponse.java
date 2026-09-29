package ages.vstable.backend.dto.compliance;

import lombok.Data;

@Data
public class LivenessStartResponse {
    private String id;
    private String sessionId;
    private String livenessUrl;
    private String validateLivenessToken;
}
