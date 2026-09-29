package ages.vstable.backend.dto.transfer;

public record TransferQuoteResponse(
        MoneyAmount source,
        MoneyAmount destination,
        ExchangeRateInfo exchangeRate,
        FeeInfo fee,
        MoneyAmount total
) {
}
