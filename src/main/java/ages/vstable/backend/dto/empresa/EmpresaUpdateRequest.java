package ages.vstable.backend.dto.empresa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmpresaUpdateRequest {

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
