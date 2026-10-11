package ages.vstable.backend.repository;

import ages.vstable.backend.entity.QuoteRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuoteRequestRepository extends JpaRepository<QuoteRequestEntity, UUID> {
}
