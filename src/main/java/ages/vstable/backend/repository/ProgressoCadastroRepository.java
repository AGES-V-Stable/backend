package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProgressoCadastroEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProgressoCadastroRepository extends JpaRepository<ProgressoCadastroEntity, UUID> {
    Optional<ProgressoCadastroEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProgressoCadastroEntity p where p.id = :id")
    Optional<ProgressoCadastroEntity> findByIdForUpdate(@Param("id") UUID id);
}
