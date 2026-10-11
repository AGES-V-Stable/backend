package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "quote_requests")
public class QuoteRequestEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private UserEntity requestedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private BeneficiaryEntity beneficiary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteDirection direction;

    @Column(name = "source_currency", nullable = false, length = 10)
    private String sourceCurrency;

    @Column(name = "target_currency", nullable = false, length = 10)
    private String targetCurrency;

    @Column(name = "source_payment_method", nullable = false, length = 50)
    private String sourcePaymentMethod;

    @Column(name = "target_payment_method", nullable = false, length = 50)
    private String targetPaymentMethod;

    @Column(name = "requested_amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal requestedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_side", nullable = false, length = 20)
    private QuoteAmountSide amountSide;

    @Column(length = 20)
    private String token;

    @Column(name = "blockchain_network", length = 50)
    private String blockchainNetwork;

    @Column(name = "cover_fees", nullable = false)
    private boolean coverFees;

    @Column(length = 128)
    private String description;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
