package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BeneficiaryProviderAccountEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryProviderAccountRepository extends JpaRepository<BeneficiaryProviderAccountEntity, UUID> {
    Optional<BeneficiaryProviderAccountEntity> findByBeneficiaryIdAndProvider(
            UUID beneficiaryId, IntegrationProvider provider);
}
