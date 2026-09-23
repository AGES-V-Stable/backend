package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "base_transactions")
@Inheritance(strategy = InheritanceType.JOINED)
public class BaseTransactionEntity {
    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "creator_user_id")
    private UUID creatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private TransactionStatus status;

    @Column(name = "foreign_currency", nullable = false, length = 3)
    private String foreignCurrency;

    @Column(name = "foreign_amount", nullable = false)
    private BigDecimal foreignAmount;

    @Column(name = "settlement_amount_brl")
    private BigDecimal settlementAmountBrl;

    @Column(name = "service_fee_brl")
    private BigDecimal serviceFeeBrl;

    @Column(name = "effective_spread_percentage")
    private BigDecimal effectiveSpreadPercentage;

    @Column(name = "exchange_rate")
    private BigDecimal exchangeRate;

    @Column(name = "avenia_ticket_id")
    private UUID aveniaTicketId;

    @Column(name = "blockchain_transaction_hash", length = 120)
    private String blockchainTransactionHash;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}

