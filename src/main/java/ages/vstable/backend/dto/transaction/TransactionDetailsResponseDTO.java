package ages.vstable.backend.dto.transaction;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionDetailsResponseDTO {
    private UUID id;
    private UUID companyId;
    private OffsetDateTime date;
    private String type;
    private String status;
    
    private BigDecimal amountForeign;
    private String foreignCurrency;
    private BigDecimal amountBrl;
    
    private BigDecimal exchangeRate;
    private BigDecimal spreadPercentage;
    private BigDecimal serviceFeeBrl;
    private BigDecimal estimatedSavingsBrl;
    
    private String counterpartyName;
    private String counterpartyDetails;
}

