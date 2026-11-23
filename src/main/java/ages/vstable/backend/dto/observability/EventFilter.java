package ages.vstable.backend.dto.observability;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record EventFilter(Instant from, Instant to,
        @Pattern(regexp = "INFO|WARN|ERROR") String severity,
        @Size(max = 80) @Pattern(regexp = "[a-z_]+") String module,
        @Size(max = 120) @Pattern(regexp = "[a-z_.]+") String action,
        @Size(max = 80) @Pattern(regexp = "[a-z_]+") String outcome,
        UUID correlationId, @Min(0) @Max(1000) Integer page, @Min(1) @Max(100) Integer size,
        @Pattern(regexp = "asc|desc") String order) { }
