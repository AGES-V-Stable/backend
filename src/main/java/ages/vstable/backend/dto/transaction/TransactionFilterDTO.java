package ages.vstable.backend.dto.transaction;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class TransactionFilterDTO {
    private OffsetDateTime startDate;
    private OffsetDateTime endDate;
    private String status;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String type; // "IMPORT" or "EXPORT"
}

