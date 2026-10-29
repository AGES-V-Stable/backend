package ages.vstable.backend.repository;

import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferExecutionRepository extends JpaRepository<TransferExecutionEntity, UUID> {

    Optional<TransferExecutionEntity> findByProviderQuoteId(UUID providerQuoteId);

    Optional<TransferExecutionEntity> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<TransferExecutionEntity> findByExternalPayinId(String externalPayinId);

    Optional<TransferExecutionEntity> findByExternalPayoutId(String externalPayoutId);

    /** Leitura com bloqueio de linha: serializa transições concorrentes (webhook x conciliação). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from TransferExecutionEntity e where e.id = :id")
    Optional<TransferExecutionEntity> findForUpdate(UUID id);

    @Query("""
            select e.id from TransferExecutionEntity e
            where e.provider = :provider and e.status in :statuses and e.updatedAt < :updatedBefore
            order by e.updatedAt
            """)
    List<UUID> findIdsToReconcile(
            IntegrationProvider provider,
            Collection<TransferExecutionStatus> statuses,
            OffsetDateTime updatedBefore,
            Pageable pageable);
}
