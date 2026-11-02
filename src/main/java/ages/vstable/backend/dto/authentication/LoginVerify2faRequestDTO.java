package ages.vstable.backend.dto.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@NoArgsConstructor
@AllArgsConstructor

public class LoginVerify2faRequestDTO {
    @NotBlank (message = "Challenge token is required")
    private String challengeToken;

    @NotBlank(message = "TOTP code is required")
    @Pattern(regexp = "\\d{6}$", message = "TOTP code must be 6 digits")
    private String code;
    
}
