package ages.vstable.backend.logging;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Métricas no formato CloudWatch EMF (Embedded Metric Format): uma linha JSON no stdout que o CloudWatch Logs
 * converte em métrica, sem agente nem chamada de rede (cabe no Lambda). Desligado por padrão
 * ({@code observability.emf.enabled}); a linha é escrita direto no stdout porque o formato exige que o
 * evento inteiro seja o JSON, o que o formatter de logs estruturados não garante.
 *
 * <p>Como em {@link OperationalLog}, só nomes fixos e classificações de baixa cardinalidade entram aqui:
 * cada combinação de dimensões vira uma métrica cobrada. Dimensões fora do padrão seguro viram {@code other}.
 */
public final class EmfMetrics {
    public static final String COUNT = "Count";
    public static final String MILLISECONDS = "Milliseconds";

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._/{}:\\-]{1,128}");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    private static volatile boolean enabled;
    private static volatile String namespace = "VStable";
    private static volatile Consumer<String> sink = new PrintStream(System.out, true)::println;

    private EmfMetrics() { }

    public record Metric(String name, double value, String unit) {
        public static Metric count(String name, long value) {
            return new Metric(name, value, COUNT);
        }

        public static Metric millis(String name, long value) {
            return new Metric(name, value, MILLISECONDS);
        }
    }

    static void configure(boolean enabled, String namespace) {
        EmfMetrics.enabled = enabled;
        EmfMetrics.namespace = namespace == null || namespace.isBlank() ? "VStable" : namespace;
    }

    /** Troca o destino das linhas (testes); {@code null} restaura o stdout. */
    static void useSink(Consumer<String> replacement) {
        sink = replacement != null ? replacement : new PrintStream(System.out, true)::println;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Emite as métricas com as dimensões dadas; nunca propaga falha para o fluxo de negócio. */
    public static void emit(Map<String, String> dimensions, Metric... metrics) {
        if (!enabled || metrics.length == 0) {
            return;
        }
        try {
            sink.accept(render(namespace, System.currentTimeMillis(), dimensions, metrics));
        } catch (RuntimeException ignored) {
            // Observabilidade é best effort: uma métrica perdida não pode derrubar a requisição.
        }
    }

    public static Map<String, String> dimensions(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    static String render(String namespace, long timestamp, Map<String, String> dimensions, Metric... metrics) {
        StringBuilder dimensionKeys = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (Map.Entry<String, String> dimension : dimensions.entrySet()) {
            if (!SAFE_NAME.matcher(dimension.getKey()).matches()) {
                throw new IllegalArgumentException("Invalid EMF dimension name");
            }
            if (!dimensionKeys.isEmpty()) {
                dimensionKeys.append(',');
            }
            dimensionKeys.append(quote(dimension.getKey()));
            values.append(',').append(quote(dimension.getKey())).append(':').append(quote(safe(dimension.getValue())));
        }
        StringBuilder definitions = new StringBuilder();
        for (Metric metric : metrics) {
            if (!SAFE_NAME.matcher(metric.name()).matches()) {
                throw new IllegalArgumentException("Invalid EMF metric name");
            }
            if (!definitions.isEmpty()) {
                definitions.append(',');
            }
            definitions.append("{\"Name\":").append(quote(metric.name()))
                    .append(",\"Unit\":").append(quote(metric.unit())).append('}');
            values.append(',').append(quote(metric.name())).append(':').append(number(metric.value()));
        }
        return "{\"_aws\":{\"Timestamp\":" + timestamp + ",\"CloudWatchMetrics\":[{\"Namespace\":" + quote(namespace)
                + ",\"Dimensions\":[[" + dimensionKeys + "]],\"Metrics\":[" + definitions + "]}]}" + values + "}";
    }

    private static String safe(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches() ? value : "other";
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
