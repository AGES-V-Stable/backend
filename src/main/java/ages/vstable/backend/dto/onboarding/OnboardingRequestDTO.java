package ages.vstable.backend.dto.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
@Schema(description = "Dados do representante e da empresa para o cadastro completo (etapa unica, antes do KYC facial)")
public class OnboardingRequestDTO {

    @NotBlank
    private String nomeCompleto;

    @NotBlank
    private String email;

    @ToString.Exclude
    @NotBlank
    private String senha;

    @ToString.Exclude
    @NotBlank
    private String confirmarSenha;

    @NotBlank
    @Size(min = 3, max = 255)
    private String razaoSocial;

    @Size(max = 255)
    private String nomeFantasia;

    @NotBlank
    private String cnpj;

    @NotBlank
    @Size(max = 100)
    private String pais;

    @NotBlank
    @Size(max = 20)
    private String cep;

    @Size(max = 255)
    private String cidade;

    @NotBlank
    @Size(max = 100)
    private String estado;
}
