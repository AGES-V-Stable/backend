package ages.vstable.backend.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica os placeholders do perfil lambda e a convivência das configurações do coletor e das métricas.
 */
class LambdaProfileConfigurationTest {

    @Test
    void baseConfiguration_keepsCollectorAndMetricDefaultsInOneDocument() throws IOException {
        // Arrange / Act
        PropertySource<?> base = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst();

        // Assert
        assertThat(base.getProperty("observability.enabled")).hasToString("${OBSERVABILITY_ENABLED:true}");
        assertThat(base.getProperty("observability.retention-days")).hasToString("${OBSERVABILITY_RETENTION_DAYS:30}");
        assertThat(base.getProperty("observability.emf.enabled")).hasToString("${EMF_METRICS_ENABLED:false}");
        assertThat(base.getProperty("observability.emf.namespace")).hasToString("${EMF_METRICS_NAMESPACE:VStable}");
    }

    @Test
    void lambdaProfile_usesConfiguredTermsAndProxyTopologyPlaceholders() throws IOException {
        // Arrange
        PropertySource<?> lambda = lambdaDocument();

        // Act / Assert
        assertThat(lambda.getProperty("app.terms.version")).hasToString("${TERMS_VERSION:1}");
        assertThat(lambda.getProperty("app.terms.terms-url")).hasToString("${TERMS_URL:https://example.com/}");
        assertThat(lambda.getProperty("app.terms.privacy-url")).hasToString("${PRIVACY_URL:https://example.com/}");
        assertThat(lambda.getProperty("app.invitation.rate-limit.trusted-proxy-hops"))
                .hasToString("${INVITATION_RATE_LIMIT_TRUSTED_PROXY_HOPS}");
        assertThat(lambda.getProperty("observability.emf.enabled")).hasToString("${EMF_METRICS_ENABLED:true}");
    }

    private static PropertySource<?> lambdaDocument() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        return documents.stream()
                .filter(document -> "lambda".equals(String.valueOf(
                        document.getProperty("spring.config.activate.on-profile"))))
                .findFirst()
                .orElseThrow();
    }
}
