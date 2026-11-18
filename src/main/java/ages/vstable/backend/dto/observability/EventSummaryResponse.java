package ages.vstable.backend.dto.observability;

import java.time.Instant;
import java.util.List;

public record EventSummaryResponse(Instant from, Instant to, String interval, Instant generatedAt,
        long total, long errors, long warnings, long infos, List<Bucket> series,
        List<ModuleErrors> errorsByModule) {
    public record Bucket(Instant from, Instant to, long error, long warn, long info) { }
    public record ModuleErrors(String module, long errors) { }
}
