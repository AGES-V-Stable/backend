package ages.vstable.backend.dto.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportChatRequestDTO {

    @NotBlank(message = "Message is required")
    @Size(max = 500, message = "Message must have at most 500 characters")
    private String message;

    @Valid
    @Size(max = 10, message = "History can contain at most 10 previous messages")
    private List<SupportChatMessageDTO> history;
}
