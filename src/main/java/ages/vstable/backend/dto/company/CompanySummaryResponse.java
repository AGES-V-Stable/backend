package ages.vstable.backend.dto.company;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Linha da listagem administrativa de clientes PME: dados da empresa, status
 * do KYC individual do responsável e o responsável pela conta (membro OWNER
 * com vínculo ativo).
 */
@Data
public class CompanySummaryResponse {

    private UUID id;
    private String legalName;
    private String tradeName;
    private String cnpj;
    private String city;
    private String state;

    private ComplianceStatus overallStatus;

    private UUID representativeId;
    private String representativeName;
    private String representativeEmail;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
