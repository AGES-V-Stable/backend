package ages.vstable.backend.external.avenia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class AveniaClientContextTest {

    @Test
    void createsAveniaClientWithAutoConfiguredRestClientBuilder() {
        // Arrange
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withUserConfiguration(AveniaClient.class, AveniaProperties.class, AveniaRequestSigner.class)
                .withPropertyValues("avenia.base-url=https://avenia.test");

        // Act
        contextRunner.run(context -> {
            // Assert
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RestClient.Builder.class);
            assertThat(context).hasSingleBean(AveniaClient.class);
            assertThat(context.getBean(AveniaGateway.class)).isSameAs(context.getBean(AveniaClient.class));
        });
    }
}
