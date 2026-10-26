package ages.vstable.backend.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaSecretsEnvironmentPostProcessorTest {

    @Test
    void nonLambdaProfile_doesNotReadSecrets() {
        AtomicBoolean called = new AtomicBoolean();
        var processor = new LambdaSecretsEnvironmentPostProcessor(arn -> {
            called.set(true);
            return "{}";
        });

        processor.postProcessEnvironment(new MockEnvironment(), null);

        assertFalse(called.get());
    }

    @Test
    void lambdaProfile_loadsRequiredAndOptionalSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONFIG_SECRET_ARN", "arn:aws:secretsmanager:example");
        environment.setActiveProfiles("lambda");
        var processor = new LambdaSecretsEnvironmentPostProcessor(arn -> {
            assertEquals("arn:aws:secretsmanager:example", arn);
            return "{\"DB_USERNAME\":\"dbuser\",\"DB_PASSWORD\":\"dbpass\","
                    + "\"JWT_SECRET\":\"test-jwt-secret\",\"BLINDPAY_API_KEY\":\"provider-key\","
                    + "\"UNEXPECTED_PROPERTY\":\"ignored\"}";
        });

        processor.postProcessEnvironment(environment, null);

        assertEquals("dbuser", environment.getProperty("DB_USERNAME"));
        assertEquals("dbpass", environment.getProperty("DB_PASSWORD"));
        assertEquals("test-jwt-secret", environment.getProperty("JWT_SECRET"));
        assertEquals("provider-key", environment.getProperty("BLINDPAY_API_KEY"));
        assertFalse(environment.containsProperty("UNEXPECTED_PROPERTY"));
    }

    @Test
    void lambdaProfile_withoutSecretArn_failsBeforeStartup() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("lambda");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LambdaSecretsEnvironmentPostProcessor(arn -> "{}")
                        .postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("APP_CONFIG_SECRET_ARN"));
    }

    @Test
    void lambdaProfile_withoutRequiredKey_failsBeforeStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONFIG_SECRET_ARN", "arn:aws:secretsmanager:example");
        environment.setActiveProfiles("lambda");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LambdaSecretsEnvironmentPostProcessor(arn -> "{\"DB_USERNAME\":\"dbuser\"}")
                        .postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().startsWith("Lambda configuration secret is missing"));
    }

    @Test
    void lambdaProfile_whenSecretServiceFails_doesNotExposeFailureDetails() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONFIG_SECRET_ARN", "arn:aws:secretsmanager:example");
        environment.setActiveProfiles("lambda");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LambdaSecretsEnvironmentPostProcessor(arn -> {
                    throw new IllegalStateException("sensitive service response");
                }).postProcessEnvironment(environment, null));

        assertEquals("Could not load Lambda configuration secret", error.getMessage());
    }
}
