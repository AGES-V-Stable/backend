package ages.vstable.backend.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportChatMessageDTO {

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "user|assistant", message = "Role must be 'user' or 'assistant'")
    private String role;

    @NotBlank(message = "Content is required")
    @Size(max = 4000, message = "Message content must have at most 4000 characters")
    private String content;
}
