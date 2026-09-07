package ages.vstable.backend.dto.empresa;

import ages.vstable.backend.entity.enums.StatusCompliance;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class SituacaoCadastralResponse {

    private UUID id;
    private String razaoSocial;
    private String nomeFantasia;
    private String cnpj;
    private StatusCompliance statusKyb;
    private StatusCompliance statusAml;
    private StatusCompliance statusGeral;
    private List<DocumentoComplianceResponse> documentos;
}
