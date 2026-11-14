package ages.vstable.backend.configuration;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Makes local dotenv values available before Spring resolves application configuration. */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final String directory;

    public DotenvEnvironmentPostProcessor() {
        this(".");
    }

    DotenvEnvironmentPostProcessor(String directory) {
        this.directory = directory;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            Dotenv.configure().directory(directory).ignoreIfMissing().load().entries()
                    .forEach(entry -> {
                        String value = entry.getValue();
                        if (System.getenv(entry.getKey()) == null && value.length() >= 2
                                && value.startsWith("'") && value.endsWith("'")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        values.put(entry.getKey(), value);
                    });
        } catch (RuntimeException ex) {
            // Parser errors may contain the original line, including credentials.
            throw new IllegalStateException("Could not load .env; check its key=value syntax");
        }
        environment.getPropertySources().addLast(new MapPropertySource("dotenv", values));
    }
}
