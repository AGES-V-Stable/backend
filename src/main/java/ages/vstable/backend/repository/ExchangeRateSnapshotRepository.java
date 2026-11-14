package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ExchangeRateSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface ExchangeRateSnapshotRepository extends JpaRepository<ExchangeRateSnapshotEntity, UUID> {

    /**
     * Grava a cotação do par, criando a linha na primeira vez. Duas requisições que
     * atualizam o mesmo par ao mesmo tempo não colidem na chave única, e uma cotação
     * mais antiga nunca substitui uma mais nova. Retorna 0 quando a linha não mudou.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO exchange_rate_snapshots (id, base_currency, quote_currency, rate, provider, fetched_at)
            VALUES (:id, :baseCurrency, :quoteCurrency, :rate, :provider, :fetchedAt)
            ON CONFLICT (base_currency, quote_currency) DO UPDATE
                SET rate = EXCLUDED.rate, provider = EXCLUDED.provider, fetched_at = EXCLUDED.fetched_at
                WHERE exchange_rate_snapshots.fetched_at < EXCLUDED.fetched_at
            """, nativeQuery = true)
    int upsert(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate, String provider,
               OffsetDateTime fetchedAt);
}
