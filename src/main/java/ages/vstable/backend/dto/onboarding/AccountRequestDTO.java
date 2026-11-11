package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
@Schema(description = "Access account of a person, created before (and independently of) any company")
public class AccountRequestDTO {

    private Boolean enableTwoFactor;

    @NotBlank
    private String fullName;

    @NotBlank
    @Email(message = "Email has an invalid format")
    private String email;

    @ToString.Exclude
    @NotBlank
    private String password;

    @ToString.Exclude
    @NotBlank
    private String confirmPassword;
}
