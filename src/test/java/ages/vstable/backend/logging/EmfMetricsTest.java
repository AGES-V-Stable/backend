package ages.vstable.backend.logging;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ages.vstable.backend.logging.EmfMetrics.Metric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmfMetricsTest {

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EmfMetrics.configure(true, "VStable/test");
        EmfMetrics.useSink(lines::add);
    }

    @AfterEach
    void tearDown() {
        EmfMetrics.configure(false, "VStable");
        EmfMetrics.useSink(null);
    }

    @Test
    void emit_writesOneValidEmfJsonLineWithNamespaceDimensionsAndMetrics() {
        // Act
        EmfMetrics.emit(EmfMetrics.dimensions("Provider", "avenia", "Outcome", "timeout"),
                Metric.count("ProviderCall", 1), Metric.millis("ProviderCallDuration", 250));

        // Assert
        assertThat(lines).hasSize(1);
        String line = lines.getFirst();
        assertThat(line).doesNotContain("\n");
        assertThat(JsonPath.<String>read(line, "$._aws.CloudWatchMetrics[0].Namespace")).isEqualTo("VStable/test");
        assertThat(JsonPath.<List<String>>read(line, "$._aws.CloudWatchMetrics[0].Dimensions[0]"))
                .containsExactly("Provider", "Outcome");
        assertThat(JsonPath.<List<String>>read(line, "$._aws.CloudWatchMetrics[0].Metrics[*].Name"))
                .containsExactly("ProviderCall", "ProviderCallDuration");
        assertThat(JsonPath.<List<String>>read(line, "$._aws.CloudWatchMetrics[0].Metrics[*].Unit"))
                .containsExactly("Count", "Milliseconds");
        assertThat(JsonPath.<Long>read(line, "$._aws.Timestamp")).isPositive();
        assertThat(JsonPath.<String>read(line, "$.Provider")).isEqualTo("avenia");
        assertThat(JsonPath.<String>read(line, "$.Outcome")).isEqualTo("timeout");
        assertThat(JsonPath.<Integer>read(line, "$.ProviderCall")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(line, "$.ProviderCallDuration")).isEqualTo(250);
    }

    @Test
    void emit_withoutDimensions_declaresAnEmptyDimensionSet() {
        // Act
        EmfMetrics.emit(EmfMetrics.dimensions(), Metric.count("UnexpectedError", 1));

        // Assert
        assertThat(JsonPath.<List<Object>>read(lines.getFirst(), "$._aws.CloudWatchMetrics[0].Dimensions[0]")).isEmpty();
    }

    @Test
    void emit_whenDisabled_writesNothing() {
        // Arrange
        EmfMetrics.configure(false, "VStable");

        // Act
        EmfMetrics.emit(EmfMetrics.dimensions(), Metric.count("UnexpectedError", 1));

        // Assert
        assertThat(lines).isEmpty();
    }

    @Test
    void emit_dimensionValueOutsideTheSafePattern_isReplacedSoItCannotLeakDataOrBreakTheJson() {
        // Act
        EmfMetrics.emit(EmfMetrics.dimensions("Route", "/v1/users/123.456.789-00 \"x\"\n"),
                Metric.count("HttpRequest", 1));

        // Assert
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Route")).isEqualTo("other");
        assertThat(lines.getFirst()).doesNotContain("123.456.789-00");
    }

    @Test
    void emit_routePatternsWithPathVariables_areKept() {
        // Act
        EmfMetrics.emit(EmfMetrics.dimensions("Route", "/v1/transfers/{id}"), Metric.count("HttpRequest", 1));

        // Assert
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Route")).isEqualTo("/v1/transfers/{id}");
    }

    @Test
    void emit_failureInTheSink_neverReachesTheCaller() {
        // Arrange
        EmfMetrics.useSink(line -> {
            throw new IllegalStateException("stdout closed");
        });

        // Act / Assert
        EmfMetrics.emit(EmfMetrics.dimensions(), Metric.count("UnexpectedError", 1));
    }

    @Test
    void render_invalidDimensionOrMetricName_isRejected() {
        // Act / Assert
        assertThatThrownBy(() -> EmfMetrics.render("N", 1, EmfMetrics.dimensions("bad name", "x"), Metric.count("M", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmfMetrics.render("N", 1, EmfMetrics.dimensions(), Metric.count("bad\"name", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
