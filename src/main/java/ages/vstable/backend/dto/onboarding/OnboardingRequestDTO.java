package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
@Schema(description = "Representative and company data for the full onboarding (single step, before facial KYC)")
public class OnboardingRequestDTO {

    @NotBlank
    private String fullName;

    @NotBlank
    private String email;

    @ToString.Exclude
    @NotBlank
    private String password;

    @ToString.Exclude
    @NotBlank
    private String confirmPassword;

    @NotBlank
    @Size(min = 3, max = 255)
    private String legalName;

    @Size(max = 255)
    private String tradeName;

    @NotBlank
    private String cnpj;

    @NotBlank
    @Size(max = 100)
    private String country;

    @NotBlank
    @Size(max = 20)
    private String zipCode;

    @Size(max = 255)
    private String city;

    @NotBlank
    @Size(max = 100)
    private String state;
}
