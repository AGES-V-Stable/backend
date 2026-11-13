package ages.vstable.backend.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que staging e produção (perfil lambda) não herdam valores provisórios: termos e topologia de proxies
 * precisam vir do ambiente, e a aplicação não sobe sem eles.
 */
class LambdaProfileConfigurationTest {

    @Test
    void lambdaProfile_requiresTermsAndProxyTopologyWithoutDefaults() throws IOException {
        // Arrange
        PropertySource<?> lambda = lambdaDocument();

        // Act / Assert
        assertThat(lambda.getProperty("app.terms.version")).hasToString("${TERMS_VERSION}");
        assertThat(lambda.getProperty("app.terms.terms-url")).hasToString("${TERMS_URL}");
        assertThat(lambda.getProperty("app.terms.privacy-url")).hasToString("${PRIVACY_URL}");
        assertThat(lambda.getProperty("app.invitation.rate-limit.trusted-proxy-hops"))
                .hasToString("${INVITATION_RATE_LIMIT_TRUSTED_PROXY_HOPS}");
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
