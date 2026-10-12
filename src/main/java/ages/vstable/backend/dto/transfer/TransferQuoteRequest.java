package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferQuoteRequest {

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @NotNull
    private TransferAmountType amountType;

    /** Opcional — quando ausente, o backend resolve a partir dos dados da operação. */
    private String sourceCurrency;

    /** Opcional — quando ausente, o backend resolve a partir dos dados da operação. */
    private String destinationCurrency;

    /**
     * Beneficiário da operação que será confirmada. Opcional no contrato, mas sem ele a
     * cotação é rejeitada com 422: é ele que determina o método de recebimento.
     */
    private UUID beneficiaryId;

    /**
     * Método de pagamento da operação que será confirmada. Opcional no contrato, mas sem
     * ele a cotação é rejeitada com 422: é ele que determina o método de entrada.
     */
    private TransferMethod paymentMethod;
}
