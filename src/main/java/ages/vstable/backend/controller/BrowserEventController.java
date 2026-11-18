package ages.vstable.backend.controller;

import ages.vstable.backend.dto.observability.BrowserEventRequest;
import ages.vstable.backend.service.observability.ObservabilityIdentity;
import ages.vstable.backend.service.observability.ObservabilityRecorder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BrowserEventController {
    private final ObservabilityRecorder recorder;

    @PostMapping("/v1/observability/browser-events")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<Void> record(@Valid @RequestBody BrowserEventRequest request, Authentication authentication) {
        var actorId = ObservabilityIdentity.id(authentication.getPrincipal());
        if (actorId == null) return ResponseEntity.status(403).build();
        recorder.browser(request, actorId);
        return ResponseEntity.accepted().build();
    }
}
