package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.entity.enums.TransferMethod;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Valores monetários são serializados como string decimal para não perder precisão. */
@Data
public class TransferResponse {

    private UUID id;
    private UUID companyId;
    private String companyName;

    private TransferDirection direction;
    /** Presente apenas em pagamentos (importação). */
    private UUID beneficiaryId;
    /** Beneficiário (pagamentos) ou pagador externo (recebimentos). */
    private String counterpartyName;
    /** Presente apenas em pagamentos (importação). */
    private TransferMethod transferMethod;

    private TransactionStatus status;

    private String foreignCurrency;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal foreignAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal settlementAmountBrl;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal serviceFeeBrl;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal exchangeRate;

    private OffsetDateTime createdAt;
    private OffsetDateTime settledAt;
}
