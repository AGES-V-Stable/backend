package ages.vstable.backend.dto.representante;

import lombok.Data;

import java.util.UUID;

@Data
public class RepresentanteResponse {

    private UUID progressoId;
    private Integer etapaAtual;
}
