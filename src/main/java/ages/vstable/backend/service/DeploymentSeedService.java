package ages.vstable.backend.service;

import ages.vstable.backend.dto.DeploymentSeedRequest;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
@Profile("deployment-seed")
public class DeploymentSeedService {
    private final AdministratorRepository administratorRepository;
    private final UserRepository userRepository;
    private final SyntheticDataSeeder syntheticDataSeeder;
    private final PasswordEncoder passwordEncoder;
    private final String environment;

    public DeploymentSeedService(AdministratorRepository administratorRepository, UserRepository userRepository,
                                 SyntheticDataSeeder syntheticDataSeeder, PasswordEncoder passwordEncoder,
                                 @Value("${SEED_ENVIRONMENT}") String environment) {
        this.administratorRepository = administratorRepository;
        this.userRepository = userRepository;
        this.syntheticDataSeeder = syntheticDataSeeder;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Transactional
    public void seed(DeploymentSeedRequest request) {
        if (request == null || request.admin() == null ||
                !StringUtils.hasText(request.admin().fullName()) ||
                request.admin().fullName().length() > 255 ||
                !StringUtils.hasText(request.admin().email()) ||
                request.admin().email().length() > 255 ||
                !request.admin().email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") ||
                request.admin().password() == null || request.admin().password().length() < 16) {
            throw new IllegalArgumentException("A valid admin name, email, and password of at least 16 characters are required");
        }
        boolean staging = switch (environment) {
            case "staging" -> true;
            case "production" -> false;
            default -> throw new IllegalStateException("Unsupported seed environment");
        };
        if (staging && (request.stagingDemoPassword() == null || request.stagingDemoPassword().length() < 16)) {
            throw new IllegalArgumentException("A staging demo password of at least 16 characters is required");
        }
        if (!staging && request.stagingDemoPassword() != null) {
            throw new IllegalArgumentException("Production seed must not include demo data");
        }

        String email = request.admin().email().trim().toLowerCase(Locale.ROOT);
        if (staging && email.equals("dev.user@example.test")) {
            throw new ConflictException("Admin email conflicts with the staging demo user");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Admin email is already in use");
        }
        AdministratorEntity existing = administratorRepository.findByEmail(email).orElse(null);
        if (existing != null && (existing.getAccessLevel() != AdminAccessLevel.SUPER_ADMIN || !existing.isEnabled())) {
            throw new ConflictException("Configured administrator is not an active super-admin");
        }
        if (existing == null) {
            if (administratorRepository.count() != 0) {
                throw new ConflictException("An administrator already exists; refusing to create a second bootstrap admin");
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            administratorRepository.saveAndFlush(AdministratorEntity.builder()
                    .fullName(request.admin().fullName().trim())
                    .email(email)
                    .passwordHash(passwordEncoder.encode(request.admin().password()))
                    .passwordSalt(BCrypt.gensalt())
                    .accessLevel(AdminAccessLevel.SUPER_ADMIN)
                    .active(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        if (staging) {
            syntheticDataSeeder.seed(request.stagingDemoPassword(), false);
        }
    }
}
