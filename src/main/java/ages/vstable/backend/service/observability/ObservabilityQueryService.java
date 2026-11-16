package ages.vstable.backend.service.observability;

import ages.vstable.backend.dto.observability.*;
import ages.vstable.backend.entity.ObservabilityEventEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.ObservabilityEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true, timeout = 5)
public class ObservabilityQueryService {
    private final ObservabilityEventRepository repository;
    private final EntityManager em;
    private final int retentionDays;

    public ObservabilityQueryService(ObservabilityEventRepository repository, EntityManager em,
            @Value("${observability.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.em = em;
        this.retentionDays = retentionDays;
    }

    public EventPageResponse list(EventFilter filter) {
        Instant now = Instant.now();
        Instant to = filter.to() == null ? now : filter.to();
        Instant from = filter.from() == null ? to.minus(7, ChronoUnit.DAYS) : filter.from();
        if (from.isAfter(to) || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0)
            throw new IllegalArgumentException("Period must be ordered and at most 90 days");
        Instant retentionStart = now.minus(retentionDays, ChronoUnit.DAYS);
        if (from.isBefore(retentionStart)) from = retentionStart;
        Specification<ObservabilityEventEntity> specification = matching(filter, from, to);
        int page = filter.page() == null ? 0 : filter.page();
        int size = filter.size() == null ? 25 : filter.size();
        Sort.Direction direction = "asc".equals(filter.order()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var result = repository.findAll(specification, PageRequest.of(page, size, Sort.by(direction, "occurredAt", "id")));
        long errors = repository.count(specification.and((root, query, cb) -> cb.equal(root.get("severity"), "ERROR")));
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Object[].class);
        var root = query.from(ObservabilityEventEntity.class);
        query.multiselect(root.get("module"), cb.count(root))
                .where(cb.and(specification.toPredicate(root, query, cb), cb.isTrue(root.get("failed"))))
                .groupBy(root.get("module")).orderBy(cb.desc(cb.count(root)));
        var failures = em.createQuery(query).getResultList().stream()
                .map(row -> new EventPageResponse.ModuleFailures((String) row[0], (Long) row[1])).toList();
        return new EventPageResponse(result.map(EventResponse::from).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages(), errors, failures);
    }

    public EventResponse detail(UUID id) {
        return repository.findById(id).filter(e -> !e.getOccurredAt().isBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS)))
                .map(EventResponse::from).orElseThrow(() -> new NotFoundException("Event not found"));
    }

    public static Specification<ObservabilityEventEntity> matching(EventFilter filter, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            Map<String, Object> equals = new HashMap<>();
            equals.put("severity", filter.severity()); equals.put("module", filter.module());
            equals.put("action", filter.action()); equals.put("outcome", filter.outcome());
            equals.put("correlationId", filter.correlationId());
            equals.forEach((key, value) -> { if (value != null) predicates.add(cb.equal(root.get(key), value)); });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
