package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.enums.IntegrationProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ProviderQuoteResult(
        IntegrationProvider provider,
        String externalQuoteId,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        BigDecimal exchangeRate,
        BigDecimal providerFee,
        BigDecimal totalFee,
        OffsetDateTime expiresAt,
        String metadata
) {
}
