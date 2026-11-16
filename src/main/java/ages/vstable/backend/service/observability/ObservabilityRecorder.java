package ages.vstable.backend.service.observability;

import ages.vstable.backend.dto.observability.BrowserEventRequest;
import ages.vstable.backend.entity.ObservabilityEventEntity;
import ages.vstable.backend.logging.OperationalLog;
import ages.vstable.backend.logging.RequestLoggingFilter;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, non-blocking ingestion. Only our safe structured loggers are consumed; never arbitrary logs. */
@Component
public class ObservabilityRecorder extends AppenderBase<ILoggingEvent> {
    private final ObservabilityWriter writer;
    @Value("${observability.enabled:true}")
    private boolean enabled = true;
    private final int retentionDays;
    private final BlockingQueue<ObservabilityEventEntity> queue = new ArrayBlockingQueue<>(2048);
    private final AtomicLong dropped = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "observability-writer");
        thread.setDaemon(true);
        return thread;
    });

    public ObservabilityRecorder(ObservabilityWriter writer,
            @Value("${observability.retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 365) throw new IllegalArgumentException("Invalid observability retention");
        this.writer = writer;
        this.retentionDays = retentionDays;
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) return;
        setName("observability-store");
        setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        start();
        for (String logger : List.of(OperationalLog.class.getName(), RequestLoggingFilter.class.getName()))
            ((LoggerContext) getContext()).getLogger(logger).addAppender(this);
        worker.scheduleWithFixedDelay(this::flush, 200, 200, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::purge, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    protected void append(ILoggingEvent log) {
        try {
            Map<String, Object> fields = new HashMap<>();
            if (log.getKeyValuePairs() != null) log.getKeyValuePairs().forEach(kv -> fields.put(kv.key, kv.value));
            var event = EventSanitizer.backend(log.getLevel().toString(), fields, log.getMDCPropertyMap());
            if (event != null) {
                event.setOccurredAt(Instant.ofEpochMilli(log.getTimeStamp()));
                enqueue(event);
            }
        } catch (RuntimeException ignored) { dropped.incrementAndGet(); }
    }

    public void browser(BrowserEventRequest request, UUID actorId) {
        if (!enabled) return;
        var event = new ObservabilityEventEntity();
        event.setId(UUID.randomUUID());
        event.setOccurredAt(Instant.now());
        event.setSeverity("ERROR");
        event.setModule(switch (request.module()) {
            case AUTH -> "authentication";
            case BENEFICIARIES -> "beneficiary";
            case TRANSFERS -> "transfer";
            case MEMBERS -> "membership";
            default -> request.module().name().toLowerCase(Locale.ROOT);
        });
        event.setAction("browser." + request.kind().name().toLowerCase(Locale.ROOT));
        event.setActorId(actorId);
        event.setOutcome("failure");
        event.setFailed(true);
        event.setMessage("Browser reported an error (untrusted)");
        event.setCorrelationId(request.correlationId());
        event.setSource("browser");
        event.setClientEventId(request.eventId());
        event.setMetadata(Map.of("trusted", false));
        enqueue(event);
    }

    private void enqueue(ObservabilityEventEntity event) {
        if (!queue.offer(event)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0)
                LoggerFactory.getLogger(ObservabilityRecorder.class).warn("Observability queue full; droppedEvents={}", count);
        }
    }

    public long droppedEvents() { return dropped.get(); }

    public void flush() {
        List<ObservabilityEventEntity> batch = new ArrayList<>();
        queue.drainTo(batch, 100);
        if (batch.isEmpty()) return;
        try {
            writer.write(batch);
        } catch (org.springframework.dao.DataIntegrityViolationException failure) {
            // Retry separately: one duplicate browser event must not discard unrelated operations.
            for (var event : batch) {
                try { writer.write(List.of(event)); }
                catch (RuntimeException ignored) { dropped.incrementAndGet(); }
            }
            LoggerFactory.getLogger(ObservabilityRecorder.class).warn("Observability batch could not be stored; droppedEvents={}", dropped.get());
        } catch (RuntimeException failure) {
            dropped.addAndGet(batch.size());
            LoggerFactory.getLogger(ObservabilityRecorder.class).warn("Observability storage unavailable; droppedEvents={}", dropped.get());
        }
    }

    private void purge() {
        try { writer.purge(Instant.now().minus(retentionDays, ChronoUnit.DAYS)); }
        catch (RuntimeException ignored) {
            LoggerFactory.getLogger(ObservabilityRecorder.class).warn("Observability retention cleanup failed");
        }
    }

    @PreDestroy
    public void close() {
        stop();
        if (getContext() != null) {
            for (String logger : List.of(OperationalLog.class.getName(), RequestLoggingFilter.class.getName()))
                ((LoggerContext) getContext()).getLogger(logger).detachAppender(this);
        }
        worker.shutdown();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        while (!queue.isEmpty()) flush();
    }
}
