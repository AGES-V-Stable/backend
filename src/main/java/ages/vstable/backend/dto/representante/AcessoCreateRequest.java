package ages.vstable.backend.dto.representante;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class AcessoCreateRequest {

    @NotBlank
    private String nomeCompleto;

    @NotBlank
    private String email;

    @ToString.Exclude
    private String senha;

    @ToString.Exclude
    private String confirmarSenha;
}
