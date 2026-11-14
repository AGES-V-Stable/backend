package ages.vstable.backend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportChatResponseDTO {

    private String reply;
    private String provider;
    private List<String> suggestedQuestions;
    private OffsetDateTime timestamp;
}
