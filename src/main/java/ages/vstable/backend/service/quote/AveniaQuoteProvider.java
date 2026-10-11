package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.external.avenia.AveniaGateway;
import ages.vstable.backend.external.avenia.AveniaApi;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
public class AveniaQuoteProvider implements QuoteProvider {

    private final AveniaGateway aveniaGateway;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.AVENIA;
    }

    @Override
    public boolean supports(QuoteContext context) {
        return true;
    }

    @Override
    public ProviderQuoteResult quote(QuoteContext context, String idempotencyKey) {
        var input = context.request();
        AveniaQuoteResponse response = aveniaGateway.createQuote(new AveniaQuoteRequest(
                input.sourceCurrency().toUpperCase(),
                input.sourcePaymentMethod(),
                input.targetCurrency().toUpperCase(),
                input.targetPaymentMethod(),
                input.amountSide() == QuoteAmountSide.SOURCE ? input.amount() : null,
                input.amountSide() == QuoteAmountSide.TARGET ? input.amount() : null,
                false,
                false,
                input.blockchainNetwork() == null ? null : AveniaApi.Quote.BlockchainSendMethod.TRANSFER.name(),
                null, null, null, null, null, null, null));

        BigDecimal fees = response.appliedFees() == null
                ? BigDecimal.ZERO
                : response.appliedFees().stream()
                    .map(AveniaAppliedFee::amount)
                    .filter(value -> value != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProviderQuoteResult(
                provider(),
                response.quoteToken(),
                response.inputAmount(),
                response.outputAmount(),
                response.basePrice(),
                fees,
                fees,
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(AveniaApi.Quote.QUOTE_TTL_SECONDS),
                "{}");
    }
}
