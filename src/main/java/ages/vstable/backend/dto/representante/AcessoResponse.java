package ages.vstable.backend.dto.representante;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import lombok.Data;

import java.util.UUID;

@Data
public class AcessoResponse {

    private UUID token;
    private UUID empresaId;
    private Integer etapaAtual;
    private StatusOnboarding statusGeral;
    private StatusCompliance statusComplianceFinal;
    private String nomeCompleto;
    private String email;
}
