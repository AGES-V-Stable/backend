package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;

import java.util.UUID;

public record QuoteContext(
        UUID quoteRequestId,
        CompanyEntity company,
        BeneficiaryEntity beneficiary,
        QuoteRequest request
) {
}
