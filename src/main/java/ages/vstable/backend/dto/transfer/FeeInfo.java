package ages.vstable.backend.dto.transfer;

import java.math.BigDecimal;

public record FeeInfo(BigDecimal percentage, BigDecimal amount, String currency) {
}
