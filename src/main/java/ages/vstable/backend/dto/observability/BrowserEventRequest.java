package ages.vstable.backend.dto.observability;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Deliberately no free text, identity, URL, timestamp, severity or arbitrary metadata from the browser. */
public record BrowserEventRequest(@NotNull UUID eventId, @NotNull UUID correlationId,
        @NotNull Kind kind, @NotNull Module module) {
    public enum Kind { UI_ERROR, UNHANDLED_REJECTION, NETWORK_FAILURE, INVALID_RESPONSE }
    public enum Module { APPLICATION, AUTH, ONBOARDING, BENEFICIARIES, TRANSFERS, MEMBERS, SETTINGS, OBSERVABILITY }
}
