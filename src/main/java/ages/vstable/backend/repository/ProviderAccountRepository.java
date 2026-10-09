package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccountEntity, UUID> {
    Optional<ProviderAccountEntity> findByCompanyIdAndProvider(UUID companyId, IntegrationProvider provider);
}
