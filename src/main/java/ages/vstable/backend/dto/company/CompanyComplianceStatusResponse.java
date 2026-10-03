package ages.vstable.backend.dto.company;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CompanyComplianceStatusResponse {

    private UUID id;
    private String legalName;
    private String tradeName;
    private String cnpj;
    private ComplianceStatus statusKyb;
    private ComplianceStatus statusAml;
    private ComplianceStatus overallStatus;
    private List<ComplianceDocumentResponse> documents;
}
