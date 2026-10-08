package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transação persistida (tabela "base_transactions"). O sentido vem da tabela
 * especializada: import_transactions (pagamento a beneficiário) ou
 * export_transactions (recebimento de pagador externo).
 */
@Entity
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
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
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "transaction_status_enum")
    private TransactionStatus status;

    @Column(name = "foreign_currency", nullable = false, length = 3)
    private String foreignCurrency;

    @Column(name = "foreign_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal foreignAmount;

    @Column(name = "settlement_amount_brl", precision = 15, scale = 2)
    private BigDecimal settlementAmountBrl;

    @Column(name = "service_fee_brl", precision = 10, scale = 2)
    private BigDecimal serviceFeeBrl;

    @Column(name = "effective_spread_percentage", precision = 5, scale = 4)
    private BigDecimal effectiveSpreadPercentage;

    @Column(name = "exchange_rate", precision = 12, scale = 6)
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
