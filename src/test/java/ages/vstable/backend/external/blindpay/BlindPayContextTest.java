package ages.vstable.backend.external.blindpay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que os beans da BlindPay sobem com a infraestrutura do Spring Boot e
 * recebem as variáveis {@code blindpay.*}, sem depender de banco ou Docker.
 */
class BlindPayContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    RestClientAutoConfiguration.class))
            .withUserConfiguration(
                    BlindPayProperties.class,
                    BlindPayClient.class,
                    BlindPayWebhookVerifier.class);

    @Test
    void wiresBlindPayBeansAndBindsEnvironmentProperties() {
        contextRunner
                .withPropertyValues(
                        "blindpay.base-url=https://api.blindpay.com",
                        "blindpay.api-key=test-api-key",
                        "blindpay.instance-id=in_000000000001",
                        "blindpay.webhook-secret=whsec_dGVzdA==")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BlindPayGateway.class);
                    assertThat(context.getBean(BlindPayGateway.class)).isInstanceOf(BlindPayClient.class);
                    assertThat(context).hasSingleBean(BlindPayWebhookVerifier.class);

                    BlindPayProperties properties = context.getBean(BlindPayProperties.class);
                    assertThat(properties.getBaseUrl()).isEqualTo("https://api.blindpay.com");
                    assertThat(properties.getApiKey()).isEqualTo("test-api-key");
                    assertThat(properties.getInstanceId()).isEqualTo("in_000000000001");
                    assertThat(properties.getWebhookSecret()).isEqualTo("whsec_dGVzdA==");
                });
    }

    @Test
    void startsWithoutCredentialsSoTheApplicationBootsBeforeBlindPayIsConfigured() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(BlindPayGateway.class));
    }
}
