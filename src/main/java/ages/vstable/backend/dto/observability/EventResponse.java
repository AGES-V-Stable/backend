package ages.vstable.backend.dto.observability;

import ages.vstable.backend.entity.ObservabilityEventEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventResponse(UUID id, Instant occurredAt, String severity, String module, String action,
        UUID actorId, String entityType, UUID entityId, String outcome, boolean failed, Long durationMs,
        String message, UUID correlationId, String source, Map<String, Object> metadata) {
    public static EventResponse from(ObservabilityEventEntity e) {
        return new EventResponse(e.getId(), e.getOccurredAt(), e.getSeverity(), e.getModule(), e.getAction(),
                e.getActorId(), e.getEntityType(), e.getEntityId(), e.getOutcome(), e.isFailed(), e.getDurationMs(),
                e.getMessage(), e.getCorrelationId(), e.getSource(), e.getMetadata());
    }
}
