package ages.vstable.backend.dto.empresa;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Dados da empresa para a segunda etapa do cadastro")
public class CadastroEmpresaRequest {

    @JsonProperty("razao_social")
    @Schema(example = "Empresa Exemplo Ltda")
    @NotBlank
    @Size(min = 3, max = 255)
    private String razaoSocial;

    @NotBlank
    @Size(max = 100)
    @Schema(example = "Brasil")
    private String pais;

    @NotBlank
    @Schema(example = "11.222.333/0001-81")
    private String cnpj;

    @NotBlank
    @Size(max = 20)
    @Schema(example = "90000-000")
    private String cep;

    @Size(max = 255)
    @Schema(example = "Porto Alegre")
    private String cidade;

    @NotBlank
    @Size(max = 100)
    @Schema(example = "RS")
    private String estado;
}
