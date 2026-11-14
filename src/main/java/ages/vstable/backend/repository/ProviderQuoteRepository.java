package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProviderQuoteEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProviderQuoteRepository extends JpaRepository<ProviderQuoteEntity, UUID> {

    /** Leitura com bloqueio de linha: serializa confirmações concorrentes da mesma oferta. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ProviderQuoteEntity q where q.id = :id")
    Optional<ProviderQuoteEntity> findForUpdate(UUID id);
}
