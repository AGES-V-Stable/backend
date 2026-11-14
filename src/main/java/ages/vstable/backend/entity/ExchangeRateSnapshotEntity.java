package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Última cotação de referência de um par, global. Gravada só pelo upsert do repositório. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "exchange_rate_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "exchange_rate_snapshots_pair_key", columnNames = {"base_currency", "quote_currency"}))
public class ExchangeRateSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "base_currency", nullable = false, length = 10)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 10)
    private String quoteCurrency;

    @Column(nullable = false, precision = 24, scale = 10)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IntegrationProvider provider;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
