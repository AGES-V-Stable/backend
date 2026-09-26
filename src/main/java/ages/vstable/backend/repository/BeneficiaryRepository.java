package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BeneficiaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntity, UUID> {
}
