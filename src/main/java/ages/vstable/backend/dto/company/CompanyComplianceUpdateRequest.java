package ages.vstable.backend.dto.company;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Data;

/** Revisão administrativa de KYB/AML. Campos nulos mantêm o status atual. */
@Data
public class CompanyComplianceUpdateRequest {

    private ComplianceStatus statusKyb;
    private ComplianceStatus statusAml;
}
