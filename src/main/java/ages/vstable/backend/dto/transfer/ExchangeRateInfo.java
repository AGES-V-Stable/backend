package ages.vstable.backend.dto.transfer;

import java.math.BigDecimal;

public record ExchangeRateInfo(String fromCurrency, String toCurrency, BigDecimal rate) {
}
