package ages.vstable.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Somente observações explicitamente selecionadas; nunca serializa principal, request ou exception. */
abstract class ImpactEvidence {
    private final List<Map<String, Object>> observations = new ArrayList<>();

    @BeforeEach
    void resetEvidence() { observations.clear(); }

    protected void observe(String step, Object value) {
        observations.add(Map.of("step", step, "value", value));
    }

    @AfterEach
    void writeEvidence(TestInfo test) throws Exception {
        String id = test.getDisplayName().split(" ")[0];
        Path directory = Path.of("build/reports/pentest-impact/observations");
        Files.createDirectories(directory);
        var result = new LinkedHashMap<String, Object>();
        result.put("case", id);
        result.put("method", test.getTestMethod().orElseThrow().getName());
        result.put("environment", "local-ephemeral-mocked-providers");
        result.put("authorization", "Bearer [REDACTED]");
        result.put("observations", List.copyOf(observations));
        Files.writeString(directory.resolve(id + ".json"), JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
