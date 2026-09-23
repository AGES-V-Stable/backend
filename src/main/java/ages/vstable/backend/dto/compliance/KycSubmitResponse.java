package ages.vstable.backend.dto.compliance;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Data;

@Data
public class KycSubmitResponse {
    private String aveniaProcessId;
    private ComplianceStatus status;
    private String resultMessage;
}
