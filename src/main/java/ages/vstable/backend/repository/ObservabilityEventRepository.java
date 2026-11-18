package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ObservabilityEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface ObservabilityEventRepository extends JpaRepository<ObservabilityEventEntity, UUID>,
        JpaSpecificationExecutor<ObservabilityEventEntity> { }
