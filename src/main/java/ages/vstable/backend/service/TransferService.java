package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.TransferCreateRequest;
import ages.vstable.backend.dto.transfer.TransferCreateResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse.CurrencyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse.ExchangeRate;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse.Fee;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {

    static final String DEFAULT_SOURCE_CURRENCY = "BRL";
    static final String DEFAULT_DESTINATION_CURRENCY = "USD";

    private final AveniaClient aveniaClient;

    public TransferQuoteResponse quote(TransferQuoteRequest request) {
        String source = resolveCurrency(request.getSourceCurrency(), DEFAULT_SOURCE_CURRENCY);
        String destination = resolveCurrency(request.getDestinationCurrency(), DEFAULT_DESTINATION_CURRENCY);

        AveniaQuoteResult quote = aveniaClient.getQuote(source, destination, request.getAmount(), request.getAmountType());

        return buildQuoteResponse(quote, source, destination);
    }

    public TransferCreateResponse create(TransferCreateRequest request) {
        String source = resolveCurrency(request.getSourceCurrency(), DEFAULT_SOURCE_CURRENCY);
        String destination = resolveCurrency(request.getDestinationCurrency(), DEFAULT_DESTINATION_CURRENCY);

        AveniaQuoteResult quote = aveniaClient.getQuote(source, destination, request.getAmount(), request.getAmountType());

        aveniaClient.createTicket(quote.quoteToken(), UUID.randomUUID(), request.getBeneficiaryId(), destination);

        return new TransferCreateResponse("PROCESSING");
    }

    private String resolveCurrency(String requested, String defaultValue) {
        return Optional.ofNullable(requested)
                .filter(s -> !s.isBlank())
                .orElse(defaultValue);
    }

    private TransferQuoteResponse buildQuoteResponse(AveniaQuoteResult quote, String sourceCurrency, String destinationCurrency) {
        String[] pair = quote.pairName().split("/", 2);
        String fromCurrency = pair[0];
        String toCurrency = pair.length > 1 ? pair[1] : destinationCurrency;

        BigDecimal totalAmount = quote.inputAmount().add(quote.markupAmount());

        return new TransferQuoteResponse(
                new CurrencyAmount(quote.inputAmount(), sourceCurrency),
                new CurrencyAmount(quote.outputAmount(), destinationCurrency),
                new ExchangeRate(fromCurrency, toCurrency, quote.basePrice()),
                new Fee(quote.markupFloatingFee(), quote.markupAmount(), quote.markupCurrency()),
                new CurrencyAmount(totalAmount, sourceCurrency)
        );
    }
}
