package ages.vstable.backend.dto.compliance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DocumentUploadStartRequest {

    @NotBlank
    @Pattern(regexp = "ID|DRIVERS-LICENSE|PASSPORT", message = "documentType deve ser ID, DRIVERS-LICENSE ou PASSPORT")
    private String documentType;

    private boolean doubleSided;
}
