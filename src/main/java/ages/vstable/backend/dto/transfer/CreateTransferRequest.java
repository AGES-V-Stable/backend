package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreateTransferRequest {

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @NotNull
    private TransferAmountType amountType;

    /** Opcional — quando ausente, o backend resolve a partir dos dados da operação. */
    private String sourceCurrency;

    /** Opcional — quando ausente, o backend resolve a partir dos dados da operação. */
    private String destinationCurrency;

    @NotNull
    private TransferMethod paymentMethod;

    @NotNull
    private UUID beneficiaryId;

    @Size(max = 68)
    private String description;

      /**
     * Código TOTP de 6 dígitos gerado pelo aplicativo autenticador do usuário.
     * Validado antes de qualquer comunicação com a Avenia (rejeitado com 401/422 se ausente ou inválido).
     */
    private String totpCode;
}
