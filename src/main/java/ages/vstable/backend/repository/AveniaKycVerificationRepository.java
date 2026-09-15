package ages.vstable.backend.repository;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AveniaKycVerificationRepository extends JpaRepository<AveniaKycVerificationEntity, UUID> {
}
