package ages.vstable.backend.dto.representante;

import lombok.Data;
import lombok.ToString;

@Data
public class AcessoCreateRequest {

    private String nomeCompleto;
    private String email;

    @ToString.Exclude
    private String senha;

    @ToString.Exclude
    private String confirmarSenha;
}
