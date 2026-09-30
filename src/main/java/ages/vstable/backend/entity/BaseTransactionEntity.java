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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "base_transactions")
public class BaseTransactionEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Column(name = "creator_user_id")
    private UUID creatorUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "transaction_status_enum")
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PROCESSING;

    @Column(name = "foreign_currency", nullable = false, length = 3)
    private String foreignCurrency;

    @Column(name = "foreign_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal foreignAmount;

    @Column(name = "settlement_amount_brl", precision = 15, scale = 2)
    private BigDecimal settlementAmountBrl;

    @Column(name = "service_fee_brl", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal serviceFeeBrl = BigDecimal.ZERO;

    @Column(name = "effective_spread_percentage", precision = 5, scale = 4)
    private BigDecimal effectiveSpreadPercentage;

    @Column(name = "exchange_rate", precision = 12, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "avenia_ticket_id", unique = true)
    private UUID aveniaTicketId;

    @Column(name = "blockchain_transaction_hash", length = 120)
    private String blockchainTransactionHash;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
