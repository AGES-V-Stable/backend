package ages.vstable.backend.service.quote;

import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Cota pela mesma rota que {@link TransferService} executa: origem BRL via PIX para
 * carteira cripto cadastrada na Avenia, na subconta do usuário. Outras combinações não
 * têm execução suportada na Avenia e por isso não geram oferta.
 */
@Component
@RequiredArgsConstructor
public class AveniaQuoteProvider implements QuoteProvider {

    private static final String BRL = "BRL";

    private final TransferService transferService;

    /**
     * Prazo para o usuário confirmar a oferta. Não é a validade do quoteToken da Avenia
     * (15 s, curta demais para uma confirmação humana): a execução sempre pede uma cotação
     * nova e recusa se o preço piorou, então um prazo maior nunca executa preço pior.
     */
    @Value("${quotes.avenia.confirmation-window:60s}")
    private Duration confirmationWindow;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.AVENIA;
    }

    @Override
    public boolean supports(QuoteContext context) {
        var request = context.request();
        return request.direction() == QuoteDirection.PAYOUT
                && BRL.equalsIgnoreCase(request.sourceCurrency())
                && TransferMethod.PIX.name().equalsIgnoreCase(request.sourcePaymentMethod())
                && context.beneficiary().getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET;
    }

    @Override
    public ProviderQuoteResult quote(QuoteContext context, String idempotencyKey) {
        TransferQuoteResponse response = transferService.quote(
                toTransferQuoteRequest(context), context.requestedBy(), context.company().getId());

        return new ProviderQuoteResult(
                provider(),
                null,
                response.source().amount(),
                response.destination().amount(),
                response.exchangeRate().rate(),
                response.fee().amount(),
                response.fee().amount(),
                OffsetDateTime.now(ZoneOffset.UTC).plus(confirmationWindow),
                "{}");
    }

    static TransferQuoteRequest toTransferQuoteRequest(QuoteContext context) {
        var input = context.request();
        TransferQuoteRequest request = new TransferQuoteRequest();
        request.setAmount(input.amount());
        request.setAmountType(toTransferAmountType(input.amountSide()));
        request.setSourceCurrency(input.sourceCurrency());
        request.setDestinationCurrency(input.targetCurrency());
        request.setBeneficiaryId(context.beneficiary().getId());
        request.setPaymentMethod(TransferMethod.PIX);
        return request;
    }

    static TransferAmountType toTransferAmountType(QuoteAmountSide amountSide) {
        return amountSide == QuoteAmountSide.SOURCE ? TransferAmountType.SOURCE : TransferAmountType.DESTINATION;
    }
}
