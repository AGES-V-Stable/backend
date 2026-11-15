package ages.vstable.backend.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.boot.EnvironmentPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path directory;

    @Test
    void loadsQuotedValuesAndResolvesApplicationPlaceholders() throws Exception {
        Files.writeString(directory.resolve(".env"), """
                # Local configuration
                DOTENV_TEST_SECRET="local-secret=with-padding=="
                DOTENV_TEST_ORIGIN='http://localhost:5173'
                """);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("application", Map.of(
                "api.auth.jwt.secret", "${DOTENV_TEST_SECRET}")));

        processor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("api.auth.jwt.secret")).isEqualTo("local-secret=with-padding==");
        assertThat(environment.getProperty("DOTENV_TEST_ORIGIN")).isEqualTo("http://localhost:5173");
    }

    @Test
    void preservesEnvironmentOverrides() throws Exception {
        Files.writeString(directory.resolve(".env"), "DOTENV_TEST_SECRET=from-file\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("DOTENV_TEST_SECRET", "from-environment")));

        processor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("DOTENV_TEST_SECRET")).isEqualTo("from-environment");
    }

    @Test
    void allowsMissingFile() {
        StandardEnvironment environment = new StandardEnvironment();

        processor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("DOTENV_TEST_SECRET")).isNull();
    }

    @Test
    void rejectsMalformedFileWithoutExposingItsContents() throws Exception {
        Files.writeString(directory.resolve(".env"), "invalid secret contents\n");

        assertThatThrownBy(() -> processor().postProcessEnvironment(
                new StandardEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not load .env; check its key=value syntax")
                .hasNoCause();
    }

    @Test
    void registersBeforeSpringLoadsConfiguration() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(DotenvEnvironmentPostProcessor.class.getName());
        assertThat(processor().getOrder()).isLessThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    private DotenvEnvironmentPostProcessor processor() {
        return new DotenvEnvironmentPostProcessor(directory.toString());
    }
}
