package ages.vstable.backend.dto.company;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Company data for account creation")
public class CompanyCreateRequest {

    @JsonProperty("razao_social")
    @Schema(example = "Example Company")
    @NotBlank
    @Size(min = 3, max = 255)
    private String razaoSocial;

    @Size(max = 255)
    private String nomeFantasia;

    @NotBlank
    @Schema(example = "11.222.333/0001-81")
    private String cnpj;

    @NotBlank
    @Size(max = 100)
    @Schema(example = "Brazil")
    private String pais;

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
