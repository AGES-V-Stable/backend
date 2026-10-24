package ages.vstable.backend.configuration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("deployment-seed")
public class DeploymentSeedSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain deploymentSeedFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/deployment-seed")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/deployment-seed").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
