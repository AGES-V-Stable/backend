package ages.vstable.backend.dto.compliance;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LivenessSubmitRequest {

    @NotBlank
    private String livenessId;
}
