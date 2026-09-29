package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AveniaTransferService {

    private final AveniaGateway aveniaGateway;

    public AveniaQuoteResponse createQuote(AveniaQuoteRequest request) {
        return aveniaGateway.createQuote(request);
    }

    public AveniaTransferResult createTransfer(
            AveniaQuoteRequest quoteRequest,
            AveniaTicketRequest ticketRequest
    ) {
        if (quoteRequest == null) {
            throw new IllegalArgumentException("quote request must be provided");
        }
        if (ticketRequest == null) {
            throw new IllegalArgumentException("ticket request must be provided");
        }
        validateCustomDuration(quoteRequest, ticketRequest);
        AveniaQuoteResponse quote = aveniaGateway.createQuote(quoteRequest);
        if (quote == null || quote.quoteToken() == null || quote.quoteToken().isBlank()) {
            throw new AveniaIntegrationException(
                    "Avenia returned a quote without quoteToken", 502, false, null);
        }

        AveniaTicketRequest requestWithCurrentQuote = ticketRequest
                .withQuoteTokenAndSubAccount(quote.quoteToken(), quoteRequest.subAccountId());
        AveniaTicketResponse ticket = aveniaGateway.createTicket(requestWithCurrentQuote);
        return new AveniaTransferResult(quote, ticket);
    }

    private void validateCustomDuration(AveniaQuoteRequest quoteRequest, AveniaTicketRequest ticketRequest) {
        Integer duration = ticketRequest.getCustomDuration();
        boolean hasCurrencyConversion = !quoteRequest.inputCurrency()
                .equalsIgnoreCase(quoteRequest.outputCurrency());
        if (duration != null
                && hasCurrencyConversion
                && duration > AveniaApi.Ticket.CUSTOM_DURATION_MAX_SECONDS_WITH_CONVERSION) {
            throw new IllegalArgumentException(
                    "customDuration cannot exceed 600 seconds when currencies are converted");
        }
    }
}
