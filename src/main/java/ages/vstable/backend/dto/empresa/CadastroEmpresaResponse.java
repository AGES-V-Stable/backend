package ages.vstable.backend.dto.empresa;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
@Schema(description = "Resultado da etapa de cadastro da empresa")
public class CadastroEmpresaResponse {

    @JsonProperty("empresa_id")
    UUID empresaId;

    @JsonProperty("progresso_cadastro_id")
    UUID progressoCadastroId;

    @JsonProperty("etapa_atual")
    Integer etapaAtual;

    @JsonProperty("proxima_etapa")
    String proximaEtapa;

    @JsonProperty("atualizado_em")
    OffsetDateTime atualizadoEm;
}
