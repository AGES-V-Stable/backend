package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccountEntity, UUID> {
    Optional<ProviderAccountEntity> findByCompanyIdAndProvider(UUID companyId, IntegrationProvider provider);

    Optional<ProviderAccountEntity> findByProviderAndExternalCustomerId(
            IntegrationProvider provider, String externalCustomerId);

    /** Serializa envios concorrentes do cadastro de uma mesma empresa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProviderAccountEntity a where a.company.id = :companyId and a.provider = :provider")
    Optional<ProviderAccountEntity> findForUpdate(UUID companyId, IntegrationProvider provider);
}
