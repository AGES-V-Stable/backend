package ages.vstable.backend.service.observability;

import ages.vstable.backend.entity.ObservabilityEventEntity;
import ages.vstable.backend.repository.ObservabilityEventRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ObservabilityWriter {
    private final ObservabilityEventRepository repository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void write(List<ObservabilityEventEntity> events) {
        repository.saveAllAndFlush(events);
    }

    /** Bounded batches avoid one long delete transaction as history grows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void purge(Instant before) {
        entityManager.createNativeQuery("DELETE FROM observability_events WHERE id IN "
                + "(SELECT id FROM observability_events WHERE occurred_at < :before ORDER BY occurred_at LIMIT 5000)")
                .setParameter("before", before).executeUpdate();
    }
}
