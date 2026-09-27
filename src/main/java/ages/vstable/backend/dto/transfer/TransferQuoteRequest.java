package ages.vstable.backend.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferQuoteRequest {

    @NotNull
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotNull
    private AmountType amountType;

    private String sourceCurrency;

    private String destinationCurrency;
}
