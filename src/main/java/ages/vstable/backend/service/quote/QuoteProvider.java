package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.enums.IntegrationProvider;

public interface QuoteProvider {
    IntegrationProvider provider();

    boolean supports(QuoteContext context);

    ProviderQuoteResult quote(QuoteContext context, String idempotencyKey);
}
