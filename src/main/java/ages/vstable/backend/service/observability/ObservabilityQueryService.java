package ages.vstable.backend.service.observability;

import ages.vstable.backend.dto.observability.*;
import ages.vstable.backend.entity.ObservabilityEventEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.ObservabilityEventRepository;
import ages.vstable.backend.repository.ObservabilityAggregationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true, timeout = 5)
public class ObservabilityQueryService {
    private final ObservabilityEventRepository repository;
    private final EntityManager em;
    private final ObservabilityAggregationRepository aggregates;
    private final int retentionDays;

    public ObservabilityQueryService(ObservabilityEventRepository repository, EntityManager em,
            ObservabilityAggregationRepository aggregates,
            @Value("${observability.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.em = em;
        this.aggregates = aggregates;
        this.retentionDays = retentionDays;
    }

    public EventPageResponse list(EventFilter filter) {
        return list(filter, window(filter));
    }

    private record Window(Instant from, Instant to) { }

    private Window window(EventFilter filter) {
        Instant now = Instant.now();
        Instant to = filter.to() == null ? now : filter.to();
        Instant from = filter.from() == null ? to.minus(7, ChronoUnit.DAYS) : filter.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0)
            throw new IllegalArgumentException("Period must be ordered and at most 90 days");
        Instant retentionStart = now.minus(retentionDays, ChronoUnit.DAYS);
        if (from.isBefore(retentionStart)) from = retentionStart.isAfter(to) ? to : retentionStart;
        return new Window(from, to);
    }

    // Both queries see the same committed snapshot, even while the collector is writing events.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public EventDashboardResponse dashboard(EventFilter filter) {
        var window = window(filter);
        return new EventDashboardResponse(summary(filter, window), list(filter, window));
    }

    private EventSummaryResponse summary(EventFilter filter, Window window) {
        var duration = Duration.between(window.from(), window.to());
        long seconds = duration.compareTo(Duration.ofHours(1)) <= 0 ? 60
                : duration.compareTo(Duration.ofDays(2)) <= 0 ? 3600 : 86400;
        String interval = seconds == 60 ? "minute" : seconds == 3600 ? "hour" : "day";
        var rows = aggregates.aggregate(filter, window.from(), window.to(), seconds);
        Map<Long, long[]> buckets = new TreeMap<>();
        // At most 91 daily, 49 hourly or 61 minute buckets, including partial boundaries.
        for (long bucket = window.from().getEpochSecond() / seconds;
                Instant.ofEpochSecond(bucket * seconds).isBefore(window.to()) && window.from().isBefore(window.to()); bucket++)
            buckets.put(bucket, new long[3]);
        Map<String, Long> modules = new HashMap<>();
        long[] totals = new long[3];
        for (var row : rows) {
            int index = switch (row.severity()) { case "ERROR" -> 0; case "WARN" -> 1; default -> 2; };
            buckets.get(row.bucket())[index] += row.events();
            totals[index] += row.events();
            if (index == 0) modules.merge(row.module(), row.events(), Long::sum);
        }
        var series = buckets.entrySet().stream().map(entry -> {
            var start = Instant.ofEpochSecond(entry.getKey() * seconds);
            var end = start.plusSeconds(seconds);
            var counts = entry.getValue();
            return new EventSummaryResponse.Bucket(start.isBefore(window.from()) ? window.from() : start,
                    end.isAfter(window.to()) ? window.to() : end, counts[0], counts[1], counts[2]);
        }).toList();
        var errorsByModule = modules.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10).map(entry -> new EventSummaryResponse.ModuleErrors(entry.getKey(), entry.getValue())).toList();
        return new EventSummaryResponse(window.from(), window.to(), interval, Instant.now(),
                totals[0] + totals[1] + totals[2], totals[0], totals[1], totals[2], series, errorsByModule);
    }

    private EventPageResponse list(EventFilter filter, Window window) {
        Specification<ObservabilityEventEntity> specification = matching(filter, window.from(), window.to());
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
            predicates.add(cb.lessThan(root.get("occurredAt"), to));
            Map<String, Object> equals = new HashMap<>();
            equals.put("severity", filter.severity()); equals.put("module", filter.module());
            equals.put("action", filter.action()); equals.put("outcome", filter.outcome());
            equals.put("correlationId", filter.correlationId());
            equals.forEach((key, value) -> { if (value != null) predicates.add(cb.equal(root.get(key), value)); });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
