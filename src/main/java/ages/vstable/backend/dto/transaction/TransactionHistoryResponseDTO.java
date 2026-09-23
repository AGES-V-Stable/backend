package ages.vstable.backend.dto.transaction;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionHistoryResponseDTO {
    private UUID id;
    private UUID companyId;
    private OffsetDateTime date;
    private String type;
    private BigDecimal amountBrl;
    private BigDecimal amountForeign;
    private String foreignCurrency;
    private String status;
    private String counterparty;
}

