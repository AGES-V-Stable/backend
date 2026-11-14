package ages.vstable.backend.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dados da empresa informados no cadastro em uma etapa e no cadastro posterior da empresa. As
 * restrições ficam nos getters para que os dois contratos sejam validados pela mesma regra, sem
 * repetir as anotações em cada DTO; o formato de CNPJ e CEP é validado na normalização.
 */
public interface CompanyRegistrationData {

    @NotBlank
    @Size(min = 3, max = 255)
    String getLegalName();

    @Size(max = 255)
    String getTradeName();

    @NotBlank
    String getCnpj();

    @NotBlank
    @Size(max = 100)
    String getCountry();

    @NotBlank
    @Size(max = 20)
    String getZipCode();

    @Size(max = 100)
    String getCity();

    @NotBlank
    @Size(max = 50)
    String getState();
}
