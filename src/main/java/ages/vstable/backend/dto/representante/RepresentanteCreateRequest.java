package ages.vstable.backend.dto.representante;

import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class RepresentanteCreateRequest {

    private UUID empresaId;
    private String nomeCompleto;
    private String cpf;
    private LocalDate dataNascimento;
    private String email;
    private String telefone;
    private String cargo;
    private String paisResidencia;
}
