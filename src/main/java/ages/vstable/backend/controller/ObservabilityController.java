package ages.vstable.backend.controller;

import ages.vstable.backend.dto.observability.*;
import ages.vstable.backend.service.observability.ObservabilityQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/observability/events")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ObservabilityController {
    private final ObservabilityQueryService service;

    @GetMapping
    public EventPageResponse list(@Valid @ModelAttribute EventFilter filter) { return service.list(filter); }

    @GetMapping("/dashboard")
    public EventDashboardResponse dashboard(@Valid @ModelAttribute EventFilter filter) { return service.dashboard(filter); }

    @GetMapping("/{id}")
    public EventResponse detail(@PathVariable UUID id) { return service.detail(id); }
}
