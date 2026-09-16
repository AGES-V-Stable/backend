package ages.vstable.backend.dto.compliance;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ComplianceSubmissionResponse {

    @JsonProperty("progresso_cadastro_id")
    private UUID progressoCadastroId;

    @JsonProperty("empresa_id")
    private UUID empresaId;

    @JsonProperty("documentos_ids")
    private List<UUID> documentosIds;

    @JsonProperty("etapa_atual")
    private Integer etapaAtual;

    @JsonProperty("status_geral")
    private StatusOnboarding statusGeral;

    @JsonProperty("status_compliance_final")
    private StatusCompliance statusComplianceFinal;

    @JsonProperty("atualizado_em")
    private OffsetDateTime atualizadoEm;
}
