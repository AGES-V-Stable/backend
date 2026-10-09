package ages.vstable.backend.dto.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuoteOfferResponse(
        UUID offerId,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        BigDecimal exchangeRate,
        BigDecimal totalFee,
        OffsetDateTime expiresAt
) {
}
