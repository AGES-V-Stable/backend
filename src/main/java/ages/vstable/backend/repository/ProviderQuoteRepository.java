package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProviderQuoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderQuoteRepository extends JpaRepository<ProviderQuoteEntity, UUID> {
}
