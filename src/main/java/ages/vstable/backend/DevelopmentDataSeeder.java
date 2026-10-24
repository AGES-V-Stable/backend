package ages.vstable.backend;

import ages.vstable.backend.service.SyntheticDataSeeder;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevelopmentDataSeeder implements CommandLineRunner {
    private final SyntheticDataSeeder syntheticDataSeeder;
    private final String password;

    public DevelopmentDataSeeder(SyntheticDataSeeder syntheticDataSeeder,
                                 @Value("${app.seed.password:}") String password) {
        this.syntheticDataSeeder = syntheticDataSeeder;
        this.password = password;
    }

    @Override
    public void run(String @NonNull ... args) {
        syntheticDataSeeder.seed(password, true);
    }
}
