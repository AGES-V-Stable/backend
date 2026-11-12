package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Cadastro em uma etapa: os dados da conta ({@link AccountRequestDTO}) e da empresa
 * ({@link CompanyRegistrationData}) no mesmo JSON plano de sempre.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "Representative and company data for the full onboarding (single step, before facial KYC)")
public class OnboardingRequestDTO extends AccountRequestDTO implements CompanyRegistrationData {

    private String legalName;
    private String tradeName;
    private String cnpj;
    private String country;
    private String zipCode;
    private String city;
    private String state;
}
