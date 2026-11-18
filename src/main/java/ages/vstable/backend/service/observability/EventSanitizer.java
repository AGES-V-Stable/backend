package ages.vstable.backend.service.observability;

import ages.vstable.backend.entity.ObservabilityEventEntity;
import java.time.Instant;
import java.util.*;

/** Projection onto safe fields. Unknown keys and all free text/payloads are discarded, rather than redacted. */
public final class EventSanitizer {
    private EventSanitizer() { }

    public static ObservabilityEventEntity backend(String severity, Map<String, Object> fields, Map<String, String> context) {
        String action = String.valueOf(fields.get("event"));
        if (!action.matches("[a-z_]+(?:\\.[a-z_]+){1,4}") || action.length() > 120) return null;
        // Browsing the event store must not create more events or recursively record ingestion failures.
        String route = String.valueOf(fields.getOrDefault("route", ""));
        if (route.startsWith("/v1/observability") || route.startsWith("/v1/admin/observability")) return null;
        var event = new ObservabilityEventEntity();
        event.setId(UUID.randomUUID());
        event.setOccurredAt(Instant.now());
        event.setSeverity(Set.of("INFO", "WARN", "ERROR").contains(severity) ? severity : "INFO");
        event.setAction(action);
        event.setModule(action.substring(0, action.indexOf('.')));
        if (action.equals("http.request.completed")) {
            if (route.contains("/transfers") || route.contains("/transferencias") || route.contains("/quotes")) event.setModule("transfer");
            else if (route.contains("/beneficiaries")) event.setModule("beneficiary");
            else if (route.contains("/invitations")) event.setModule("invitation");
            else if (route.contains("/members")) event.setModule("membership");
            else if (route.contains("/companies")) event.setModule("company");
            else if (route.contains("/auth")) event.setModule("authentication");
            else if (route.contains("/onboarding") || route.contains("/compliance")) event.setModule("onboarding");
        }
        String outcome = String.valueOf(fields.getOrDefault("outcome", "failure"));
        Set<String> outcomes = Set.of("success", "failure", "partial", "http_failure", "communication_failure", "timeout",
                "unexpected_failure", "unavailable", "pending", "under_review", "approved", "rejected",
                "invalid_token", "invalid_credentials", "account_locked", "account_unavailable", "token_expired", "token_invalid",
                "awaiting_payment", "processing", "settled", "failed", "partial_failure", "canceled", "held");
        event.setOutcome(outcomes.contains(outcome) ? outcome : "failure");
        event.setFailed(Set.of("failure", "partial", "http_failure", "communication_failure", "timeout", "unexpected_failure",
                "unavailable", "rejected", "invalid_token", "invalid_credentials", "account_locked", "account_unavailable", "token_expired",
                "token_invalid", "failed", "partial_failure", "canceled").contains(event.getOutcome()));
        event.setActorId(uuid(context.get("actorId")));
        event.setCorrelationId(uuid(context.get("requestId")));
        event.setEntityId(uuid(fields.get("entityId")));
        String entityType = String.valueOf(fields.get("entityType"));
        if (Set.of("company", "beneficiary", "transfer", "membership", "invitation", "user").contains(entityType))
            event.setEntityType(entityType);
        Object duration = fields.get("durationMs");
        if (duration instanceof Number n && n.longValue() >= 0) event.setDurationMs(n.longValue());
        event.setMessage("Application event: " + action);
        event.setSource("backend");
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("status", "timeoutCount")) {
            if (fields.get(key) instanceof Number n) safe.put(key, n.longValue());
        }
        if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "OTHER").contains(String.valueOf(fields.get("method"))))
            safe.put("method", fields.get("method"));
        if (route.length() <= 180 && route.matches("/[a-zA-Z0-9/{}_-]+")) safe.put("route", route);
        if (fields.get("provider") instanceof String p && Set.of("avenia", "blindpay", "Avenia", "BlindPay").contains(p))
            safe.put("provider", p);
        if (fields.get("exceptionType") instanceof String type && type.length() <= 160
                && type.matches("[a-zA-Z0-9_.$]+")) safe.put("exceptionType", type);
        if (fields.get("operation") instanceof String operation && operation.length() <= 120
                && operation.matches("(?:get|post|put|delete|patch)\\.[A-Za-z]+Response")) safe.put("operation", operation);
        event.setMetadata(safe);
        return event;
    }

    public static UUID uuid(Object value) {
        try {
            String text = String.valueOf(value);
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
