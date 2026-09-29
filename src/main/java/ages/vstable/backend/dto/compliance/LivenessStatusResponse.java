package ages.vstable.backend.dto.compliance;

import lombok.Data;

@Data
public class LivenessStatusResponse {
    private boolean ready;
    private String status;
}
