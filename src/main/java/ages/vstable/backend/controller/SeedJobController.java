package ages.vstable.backend.controller;

import ages.vstable.backend.service.InitialAdministratorSeeder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("seed")
public class SeedJobController {
    private final InitialAdministratorSeeder seeder;

    public SeedJobController(InitialAdministratorSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/events")
    public Map<String, String> seed(@Valid @RequestBody InitialAdminRequest request) {
        boolean created = seeder.seed(request.fullName(), request.email(), request.password());
        return Map.of("status", created ? "seeded" : "already_exists");
    }

    public record InitialAdminRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12) String password) {
    }
}
