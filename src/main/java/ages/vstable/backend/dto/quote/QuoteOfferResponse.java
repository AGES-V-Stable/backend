package ages.vstable.backend.dto.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Melhor oferta; {@code provider} indica quem executará a transferência se ela for confirmada. */
public record QuoteOfferResponse(
        UUID offerId,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        BigDecimal exchangeRate,
        BigDecimal totalFee,
        OffsetDateTime expiresAt,
        String provider
) {
}
