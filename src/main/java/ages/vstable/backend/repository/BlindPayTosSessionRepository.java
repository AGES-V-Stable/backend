package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BlindPayTosSessionRepository extends JpaRepository<BlindPayTosSessionEntity, UUID> {
    Optional<BlindPayTosSessionEntity> findByIdempotencyKey(UUID idempotencyKey);
}
