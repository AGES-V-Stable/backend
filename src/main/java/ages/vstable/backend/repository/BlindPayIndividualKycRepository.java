package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BlindPayIndividualKycEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface BlindPayIndividualKycRepository extends JpaRepository<BlindPayIndividualKycEntity, UUID> {
    Optional<BlindPayIndividualKycEntity> findByCustomerId(String customerId);
    Optional<BlindPayIndividualKycEntity> findByTosSessionKey(UUID tosSessionKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from BlindPayIndividualKycEntity k where k.userId = :userId")
    Optional<BlindPayIndividualKycEntity> findForUpdate(UUID userId);
}
