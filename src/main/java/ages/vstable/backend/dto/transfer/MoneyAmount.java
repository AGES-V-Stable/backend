package ages.vstable.backend.dto.transfer;

import java.math.BigDecimal;

public record MoneyAmount(BigDecimal amount, String currency) {
}
