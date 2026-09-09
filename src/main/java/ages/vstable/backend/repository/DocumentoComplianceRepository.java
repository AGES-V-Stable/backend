package ages.vstable.backend.repository;

import ages.vstable.backend.entity.DocumentoComplianceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentoComplianceRepository extends JpaRepository<DocumentoComplianceEntity, UUID> {

    List<DocumentoComplianceEntity> findByEmpresaId(UUID empresaId);
}
