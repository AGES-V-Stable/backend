package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Empresa cadastrada por um usuário autenticado ainda sem empresa; as restrições vêm de {@link CompanyRegistrationData}. */
@Data
@Schema(description = "Company registered by an authenticated user without a company; the user becomes its owner")
public class CompanyRegistrationRequestDTO implements CompanyRegistrationData {

    private String legalName;
    private String tradeName;
    private String cnpj;
    private String country;
    private String zipCode;
    private String city;
    private String state;
}
