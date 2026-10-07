package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.IntegrationProvider;
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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "provider_quotes", uniqueConstraints = {
        @UniqueConstraint(name = "provider_quotes_request_provider_key", columnNames = {"quote_request_id", "provider"}),
        @UniqueConstraint(name = "provider_quotes_idempotency_key", columnNames = "idempotency_key")
})
public class ProviderQuoteEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_request_id", nullable = false)
    private QuoteRequestEntity quoteRequest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IntegrationProvider provider;

    @Column(name = "external_quote_id")
    private String externalQuoteId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "source_amount", precision = 20, scale = 8)
    private BigDecimal sourceAmount;

    @Column(name = "target_amount", precision = 20, scale = 8)
    private BigDecimal targetAmount;

    @Column(name = "exchange_rate", precision = 20, scale = 10)
    private BigDecimal exchangeRate;

    @Column(name = "provider_fee", precision = 20, scale = 8)
    private BigDecimal providerFee;

    @Column(name = "service_fee", precision = 20, scale = 8)
    private BigDecimal serviceFee;

    @Column(name = "total_fee", precision = 20, scale = 8)
    private BigDecimal totalFee;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_metadata", columnDefinition = "jsonb", nullable = false)
    private String providerMetadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
