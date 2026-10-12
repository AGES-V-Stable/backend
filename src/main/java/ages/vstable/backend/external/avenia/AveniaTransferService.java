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
        if (request == null) {
            throw new IllegalArgumentException("quote request must be provided");
        }
        return requireValidQuote(aveniaGateway.createQuote(request), request);
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
        AveniaQuoteResponse quote = requireValidQuote(aveniaGateway.createQuote(quoteRequest), quoteRequest);

        AveniaTicketRequest requestWithCurrentQuote = ticketRequest
                .withQuoteTokenAndSubAccount(quote.quoteToken(), quoteRequest.subAccountId());
        AveniaTicketResponse ticket = aveniaGateway.createTicket(requestWithCurrentQuote);
        if (ticket == null || ticket.id() == null) {
            throw new AveniaIntegrationException(
                    "Avenia returned an incomplete ticket", 502, false, null);
        }
        return new AveniaTransferResult(quote, ticket);
    }

    private AveniaQuoteResponse requireValidQuote(
            AveniaQuoteResponse quote, AveniaQuoteRequest request) {
        if (quote == null
                || quote.quoteToken() == null || quote.quoteToken().isBlank()
                || quote.inputCurrency() == null || quote.inputCurrency().isBlank()
                || quote.outputCurrency() == null || quote.outputCurrency().isBlank()
                || quote.inputPaymentMethod() == null || quote.inputPaymentMethod().isBlank()
                || quote.outputPaymentMethod() == null || quote.outputPaymentMethod().isBlank()
                || quote.inputAmount() == null || quote.outputAmount() == null
                || quote.inputAmount().signum() <= 0 || quote.outputAmount().signum() <= 0
                || quote.basePrice() == null || quote.basePrice().signum() <= 0) {
            throw new AveniaIntegrationException(
                    "Avenia returned an incomplete quote", 502, false, null);
        }
        if (!quote.inputCurrency().equalsIgnoreCase(request.inputCurrency())
                || !quote.outputCurrency().equalsIgnoreCase(request.outputCurrency())
                || !quote.inputPaymentMethod().equalsIgnoreCase(request.inputPaymentMethod())
                || !quote.outputPaymentMethod().equalsIgnoreCase(request.outputPaymentMethod())) {
            throw new AveniaIntegrationException(
                    "Avenia returned a quote that does not match the request", 502, false, null);
        }
        return quote;
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
