package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ImportTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImportTransactionRepository extends JpaRepository<ImportTransactionEntity, UUID> {
}
