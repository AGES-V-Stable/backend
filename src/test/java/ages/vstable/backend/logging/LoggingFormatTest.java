package ages.vstable.backend.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingFormatTest {
    @Configuration(proxyBeanMethods = false)
    static class LoggingApplication { }

    @Test
    void bootEmitsParseableStructuredJsonAndReadableLocalEvents(CapturedOutput output) throws Exception {
        try {
            org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader()).cleanUp();
            try (var context = SpringApplication.run(LoggingApplication.class,
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                    "--logging.structured.format.console=logstash")) {
                MDC.put("requestId", "format-request");
                OperationalLog.milestone("format.json.checked", "success");
                OperationalLog.provider("avenia", "quote.fetch", "success", 200, System.nanoTime());
                OperationalLog.unexpected(new IllegalStateException("synthetic-sensitive-message"));
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                new RequestLoggingFilter().doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/"),
                        response, (req, res) -> response.setStatus(401));
            }
            String json = output.getOut().lines().filter(line -> line.contains("format.json.checked")).findFirst().orElseThrow();
            var parsed = new ObjectMapper().readTree(json);
            assertThat(parsed.get("event").asText()).isEqualTo("format.json.checked");
            assertThat(parsed.get("outcome").asText()).isEqualTo("success");
            assertThat(parsed.get("requestId").asText()).isEqualTo("format-request");
            assertThat(parsed.get("level").asText()).isEqualTo("INFO");
            assertThat(parsed.has("@timestamp")).isTrue();
            var mapper = new ObjectMapper();
            var provider = mapper.readTree(output.getOut().lines()
                    .filter(line -> line.contains("provider.call.completed")).findFirst().orElseThrow());
            assertThat(provider.get("durationMs").isNumber()).isTrue();
            assertThat(provider.get("status").asInt()).isEqualTo(200);
            var request = mapper.readTree(output.getOut().lines()
                    .filter(line -> line.contains("http.request.completed")).findFirst().orElseThrow());
            assertThat(request.get("level").asText()).isEqualTo("WARN");
            assertThat(request.get("status").asInt()).isEqualTo(401);
            String diagnostic = output.getOut().lines().filter(line -> line.contains("application.error")).findFirst().orElseThrow();
            assertThat(mapper.readTree(diagnostic).get("stackFrames").asText()).contains("IllegalStateException", "LoggingFormatTest");
            assertThat(diagnostic).doesNotContain("synthetic-sensitive-message");
            org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader()).cleanUp();
            try (var context = SpringApplication.run(LoggingApplication.class,
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                    "--logging.structured.format.console=")) {
                OperationalLog.milestone("format.text.checked", "success");
            }
            String text = output.getOut().lines().filter(line -> line.contains("format.text.checked")).findFirst().orElseThrow();
            assertThat(text).contains("INFO", "requestId=format-request", "event=", "outcome=").doesNotStartWith("{");
        } finally {
            MDC.clear();
        }
    }
}
