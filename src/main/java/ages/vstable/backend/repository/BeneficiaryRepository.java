package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BeneficiaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface BeneficiaryRepository
        extends JpaRepository<BeneficiaryEntity, UUID>,
        JpaSpecificationExecutor<BeneficiaryEntity> {
    List<BeneficiaryEntity> findByCompanyId(UUID companyId);

    Optional<BeneficiaryEntity> findByIdAndCompanyId(UUID id, UUID companyId);
}
