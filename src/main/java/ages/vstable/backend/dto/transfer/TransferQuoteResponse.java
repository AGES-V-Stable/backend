package ages.vstable.backend.dto.transfer;

import java.math.BigDecimal;

public record TransferQuoteResponse(
        CurrencyAmount source,
        CurrencyAmount destination,
        ExchangeRate exchangeRate,
        Fee fee,
        CurrencyAmount total
) {

    public record CurrencyAmount(BigDecimal amount, String currency) {}

    public record ExchangeRate(String fromCurrency, String toCurrency, BigDecimal rate) {}

    public record Fee(BigDecimal percentage, BigDecimal amount, String currency) {}
}
