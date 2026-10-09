package ages.vstable.backend.dto.quote;

import java.util.UUID;

public record QuoteResponse(
        UUID quoteRequestId,
        QuoteOfferResponse offer
) {
}
