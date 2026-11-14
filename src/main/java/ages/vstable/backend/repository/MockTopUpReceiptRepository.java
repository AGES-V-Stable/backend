package ages.vstable.backend.repository;

import ages.vstable.backend.entity.MockTopUpReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MockTopUpReceiptRepository extends JpaRepository<MockTopUpReceiptEntity, UUID> {
    Optional<MockTopUpReceiptEntity> findByCompanyIdAndRequestKey(UUID companyId, String requestKey);
}
