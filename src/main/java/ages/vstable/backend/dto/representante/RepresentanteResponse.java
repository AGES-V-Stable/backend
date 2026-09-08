package ages.vstable.backend.dto.representante;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class RepresentanteResponse {

    private UUID id;
    private UUID empresaId;
    private String nomeCompleto;
    private String email;
    private OffsetDateTime criadoEm;
    private OffsetDateTime atualizadoEm;
}
