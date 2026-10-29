package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;

import java.util.UUID;

/**
 * Dados de uma cotação. {@code requestedBy} é o usuário que a pediu: a Avenia cota e
 * executa na subconta dele, então a cotação precisa ser feita no mesmo contexto.
 */
public record QuoteContext(
        UUID quoteRequestId,
        CompanyEntity company,
        BeneficiaryEntity beneficiary,
        QuoteRequest request,
        UUID requestedBy
) {
}
