package ages.vstable.backend.dto.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanyRequest {

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
