package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

import java.util.List;

@Data
public class AveniaKycAttemptsResponse {
    private List<AveniaKycAttempt> attempts;
    private String cursor;
}
