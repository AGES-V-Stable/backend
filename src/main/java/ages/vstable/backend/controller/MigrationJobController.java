package ages.vstable.backend.controller;

import ages.vstable.backend.service.DatabaseMigrationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("migration")
public class MigrationJobController {
    private final DatabaseMigrationService migrationService;

    public MigrationJobController(DatabaseMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/events")
    public Map<String, String> migrate() {
        migrationService.migrate();
        return Map.of("status", "migrated");
    }
}
