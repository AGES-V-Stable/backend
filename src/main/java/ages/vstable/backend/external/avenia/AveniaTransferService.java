package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

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
        return createTransfer(quoteRequest, ticketRequest, quote -> { });
    }

    /**
     * Igual a {@link #createTransfer(AveniaQuoteRequest, AveniaTicketRequest)}, mas chama
     * {@code beforeTicket} com a cotação nova já validada e antes de criar o ticket. Se
     * {@code beforeTicket} lançar exceção, nenhum ticket é criado — é o ponto para recusar
     * uma cotação cujo preço mudou em relação ao que o usuário confirmou.
     */
    public AveniaTransferResult createTransfer(
            AveniaQuoteRequest quoteRequest,
            AveniaTicketRequest ticketRequest,
            Consumer<AveniaQuoteResponse> beforeTicket
    ) {
        if (quoteRequest == null) {
            throw new IllegalArgumentException("quote request must be provided");
        }
        if (ticketRequest == null) {
            throw new IllegalArgumentException("ticket request must be provided");
        }
        validateExternalId(ticketRequest);
        validateCustomDuration(quoteRequest, ticketRequest);
        AveniaQuoteResponse quote = requireValidQuote(aveniaGateway.createQuote(quoteRequest), quoteRequest);
        beforeTicket.accept(quote);

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
        if (request.feeSettlement() != null && !request.feeSettlement().isBlank()
                && (quote.feeSettlement() == null
                || !quote.feeSettlement().equalsIgnoreCase(request.feeSettlement()))) {
            throw new AveniaIntegrationException(
                    "Avenia returned a quote with an unexpected fee settlement mode", 502, false, null);
        }
        validateFinancialData(quote);
        return quote;
    }

    private void validateExternalId(AveniaTicketRequest ticketRequest) {
        if (ticketRequest.getExternalId() != null
                && ticketRequest.getExternalId().length() > AveniaApi.Ticket.EXTERNAL_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("externalId must have at most 68 characters");
        }
    }

    /**
     * Valida a composição financeira antes de usar o quoteToken. Isso é especialmente
     * importante em createTransfer: uma quote inconsistente não pode originar um ticket.
     */
    private void validateFinancialData(AveniaQuoteResponse quote) {
        List<AveniaAppliedFee> appliedFees = quote.appliedFees();
        BigDecimal feeAmount;
        if (appliedFees != null && !appliedFees.isEmpty()) {
            feeAmount = BigDecimal.ZERO;
            for (AveniaAppliedFee fee : appliedFees) {
                if (fee == null || fee.amount() == null || fee.amount().signum() < 0
                        || fee.currency() == null || fee.currency().isBlank()) {
                    throw incompleteQuote();
                }
                if (!fee.currency().equalsIgnoreCase(quote.inputCurrency())) {
                    throw inconsistentQuoteCurrency();
                }
                feeAmount = feeAmount.add(fee.amount());
            }
        } else {
            feeAmount = quote.markupAmount() == null ? BigDecimal.ZERO : quote.markupAmount();
            if (feeAmount.signum() < 0) {
                throw incompleteQuote();
            }
            if (feeAmount.signum() > 0
                    && (quote.markupCurrency() == null || quote.markupCurrency().isBlank())) {
                throw incompleteQuote();
            }
            if (feeAmount.signum() > 0
                    && !quote.markupCurrency().equalsIgnoreCase(quote.inputCurrency())) {
                throw inconsistentQuoteCurrency();
            }
        }
        if (feeAmount.compareTo(quote.inputAmount()) > 0) {
            throw new AveniaIntegrationException(
                    "Avenia returned inconsistent financial data in the quote", 502, false, null);
        }
    }

    private AveniaIntegrationException incompleteQuote() {
        return new AveniaIntegrationException(
                "Avenia returned an incomplete quote", 502, false, null);
    }

    private AveniaIntegrationException inconsistentQuoteCurrency() {
        return new AveniaIntegrationException(
                "Avenia returned fees in a currency different from the input currency", 502, false, null);
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
