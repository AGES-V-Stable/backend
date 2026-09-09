package ages.vstable.backend.dto.representante;

import lombok.Data;

import java.util.UUID;

@Data
public class AcessoResponse {

    private UUID token;
    private UUID empresaId;
    private Integer etapaAtual;
    private String nomeCompleto;
    private String email;
}
