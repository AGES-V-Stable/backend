package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ExportTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExportTransactionRepository extends JpaRepository<ExportTransactionEntity, UUID> {
}
