package ages.vstable.backend.controller;

import ages.vstable.backend.dto.DeploymentSeedRequest;
import ages.vstable.backend.service.DeploymentSeedService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("deployment-seed")
public class DeploymentSeedController {
    private final DeploymentSeedService seedService;
    private final ObjectMapper objectMapper;

    public DeploymentSeedController(DeploymentSeedService seedService, ObjectMapper objectMapper) {
        this.seedService = seedService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/internal/deployment-seed")
    public ResponseEntity<Map<String, Boolean>> seed(@RequestBody String payload) {
        DeploymentSeedRequest request;
        try {
            request = objectMapper.readValue(payload, DeploymentSeedRequest.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid seed payload");
        }
        seedService.seed(request);
        return ResponseEntity.ok(Map.of("seeded", true));
    }
}
