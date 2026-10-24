package ages.vstable.backend.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Loads Lambda secrets before Spring creates the datasource and authentication beans. */
public class LambdaSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SECRET_ARN_PROPERTY = "APP_CONFIG_SECRET_ARN";
    private static final Set<String> REQUIRED_KEYS = Set.of("DB_USERNAME", "DB_PASSWORD", "JWT_SECRET");
    private static final Set<String> OPTIONAL_KEYS = Set.of(
            "AVENIA_API_KEY", "AVENIA_PRIVATE_KEY", "AVENIA_EMAIL", "AVENIA_PASSWORD",
            "BLINDPAY_API_KEY", "BLINDPAY_INSTANCE_ID", "BLINDPAY_WEBHOOK_SECRET");

    private final Function<String, String> secretReader;

    public LambdaSecretsEnvironmentPostProcessor() {
        this(LambdaSecretsEnvironmentPostProcessor::readSecret);
    }

    LambdaSecretsEnvironmentPostProcessor(Function<String, String> secretReader) {
        this.secretReader = secretReader;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("lambda"))) {
            return;
        }

        String secretArn = environment.getProperty(SECRET_ARN_PROPERTY);
        if (secretArn == null || secretArn.isBlank()) {
            throw new IllegalStateException("APP_CONFIG_SECRET_ARN is required for the lambda profile");
        }

        String secretJson;
        try {
            secretJson = secretReader.apply(secretArn);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not load Lambda configuration secret");
        }

        Map<String, Object> values = parseSecret(secretJson);
        environment.getPropertySources().addFirst(new MapPropertySource("lambdaSecrets", values));
    }

    private static Map<String, Object> parseSecret(String secretJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(secretJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException();
            }

            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : REQUIRED_KEYS) {
                JsonNode value = root.get(key);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalStateException("Lambda configuration secret is missing " + key);
                }
                values.put(key, value.asText());
            }
            for (String key : OPTIONAL_KEYS) {
                JsonNode value = root.get(key);
                if (value != null && value.isTextual()) {
                    values.put(key, value.asText());
                }
            }
            return values;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Lambda configuration secret must be a JSON object");
        }
    }

    private static String readSecret(String secretArn) {
        try (SecretsManagerClient client = SecretsManagerClient.create()) {
            return client.getSecretValue(GetSecretValueRequest.builder().secretId(secretArn).build()).secretString();
        }
    }
}
