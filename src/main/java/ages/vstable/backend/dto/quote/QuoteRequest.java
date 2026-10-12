package ages.vstable.backend.dto.quote;

import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record QuoteRequest(
        @NotNull UUID beneficiaryId,
        @NotNull QuoteDirection direction,
        @NotBlank @Size(max = 10) String sourceCurrency,
        @NotBlank @Size(max = 10) String targetCurrency,
        @NotBlank @Size(max = 50) String sourcePaymentMethod,
        @NotBlank @Size(max = 50) String targetPaymentMethod,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull QuoteAmountSide amountSide,
        @Size(max = 20) String token,
        @Size(max = 50) String blockchainNetwork,
        boolean coverFees,
        @Size(max = 128) String description
) {
}
