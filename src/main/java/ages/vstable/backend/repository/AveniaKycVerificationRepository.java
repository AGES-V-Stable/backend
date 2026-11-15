package ages.vstable.backend.repository;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AveniaKycVerificationRepository extends JpaRepository<AveniaKycVerificationEntity, UUID> {
    Optional<AveniaKycVerificationEntity> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    List<AveniaKycVerificationEntity> findByUserIdInOrderByCreatedAtDesc(List<UUID> userIds);

    boolean existsByAveniaSubAccountId(String aveniaSubAccountId);
}
