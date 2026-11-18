package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "observability_events")
@Getter
@Setter
@NoArgsConstructor
public class ObservabilityEventEntity {
    @Id private UUID id;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false, length = 8) private String severity;
    @Column(nullable = false, length = 80) private String module;
    @Column(nullable = false, length = 120) private String action;
    private UUID actorId;
    @Column(length = 40) private String entityType;
    private UUID entityId;
    @Column(nullable = false, length = 80) private String outcome;
    @Column(nullable = false) private boolean failed;
    private Long durationMs;
    @Column(nullable = false, length = 160) private String message;
    private UUID correlationId;
    @Column(nullable = false, length = 16) private String source;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> metadata;
    private UUID clientEventId;
}
