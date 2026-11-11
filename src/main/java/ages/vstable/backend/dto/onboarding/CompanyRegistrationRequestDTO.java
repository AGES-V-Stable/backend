package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Company registered by an authenticated user without a company; the user becomes its owner")
public class CompanyRegistrationRequestDTO {

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

    @Size(max = 100)
    private String city;

    @NotBlank
    @Size(max = 50)
    private String state;
}
