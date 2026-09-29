package ages.vstable.backend.dto.compliance;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Dados pessoais do representante enviados direto para a Avenia ao finalizar
 * o KYC. Por decisão de compliance, nenhum desses campos é persistido no
 * nosso banco — só o id do processo de KYC retornado pela Avenia é guardado.
 */
@Data
public class KycSubmitRequest {

    @NotBlank
    private String fullName;

    @NotBlank
    private String dateOfBirth;

    @NotBlank
    private String taxIdNumber;

    @NotBlank
    private String email;

    @NotBlank
    private String phone;

    @NotBlank
    private String country;

    @NotBlank
    private String state;

    @NotBlank
    private String city;

    @NotBlank
    private String zipCode;

    @NotBlank
    private String streetAddress;
}
