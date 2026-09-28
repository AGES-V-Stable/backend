package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ComplianceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplianceDocumentRepository extends JpaRepository<ComplianceDocumentEntity, UUID> {
    List<ComplianceDocumentEntity> findByCompanyId(UUID companyId);
}
