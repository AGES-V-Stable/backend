package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BaseTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BaseTransactionRepository extends JpaRepository<BaseTransactionEntity, UUID> {
}
