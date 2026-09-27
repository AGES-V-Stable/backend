package ages.vstable.backend.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferCreateRequest {

    @NotNull
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotNull
    private AmountType amountType;

    private String sourceCurrency;

    private String destinationCurrency;

    @NotNull
    private PaymentMethod paymentMethod;

    @NotNull
    private UUID beneficiaryId;

    private String description;
}
