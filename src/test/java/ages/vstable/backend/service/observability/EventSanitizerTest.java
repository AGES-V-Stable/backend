package ages.vstable.backend.service.observability;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class EventSanitizerTest {
    @Test
    void projectsOnlySafeFieldsAndDiscardsSecretsAndFreeText() {
        UUID actor = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        var fields = Map.<String, Object>ofEntries(
                Map.entry("event", "provider.call.completed"), Map.entry("outcome", "timeout"),
                Map.entry("provider", "avenia"), Map.entry("durationMs", 52L),
                Map.entry("password", "synthetic-password"), Map.entry("token", "synthetic-token"),
                Map.entry("cookie", "synthetic-cookie"), Map.entry("email", "person@example.test"),
                Map.entry("message", "Bearer synthetic-token"), Map.entry("payload", Map.of("cpf", "synthetic-document")),
                Map.entry("route", "/v1/people/person@example.test?token=synthetic-token"));

        var event = EventSanitizer.backend("WARN", fields, Map.of("actorId", actor.toString(), "requestId", correlation.toString()));

        assertThat(event.getMetadata()).containsExactlyEntriesOf(Map.of("provider", "avenia"));
        assertThat(event.getMessage()).isEqualTo("Application event: provider.call.completed");
        assertThat(event.getActorId()).isEqualTo(actor);
        assertThat(event.getCorrelationId()).isEqualTo(correlation);
        assertThat(event.getDurationMs()).isEqualTo(52);
        assertThat(event.isFailed()).isTrue();
    }

    @Test
    void ignoresObservabilityRequestsAndInvalidEvents() {
        assertThat(EventSanitizer.backend("INFO", Map.of("event", "http.request.completed", "route", "/v1/admin/observability/events"), Map.of())).isNull();
        assertThat(EventSanitizer.backend("ERROR", Map.of("event", "password=secret"), Map.of())).isNull();
    }

    @Test
    void preservesSafeEntityAndRouteAndRejectsForgedIdentity() {
        UUID id = UUID.randomUUID();
        var event = EventSanitizer.backend("INFO", Map.of("event", "company.update.completed", "outcome", "success",
                "entityId", id, "entityType", "company", "route", "/v1/companies/{id}", "method", "PUT"),
                Map.of("actorId", "user@example.test", "requestId", "not-a-uuid"));
        assertThat(event.getActorId()).isNull();
        assertThat(event.getCorrelationId()).isNull();
        assertThat(event.getEntityId()).isEqualTo(id);
        assertThat(event.getMetadata()).containsEntry("route", "/v1/companies/{id}");
        assertThat(event.isFailed()).isFalse();
    }
}
