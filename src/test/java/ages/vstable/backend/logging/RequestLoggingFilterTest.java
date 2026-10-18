package ages.vstable.backend.logging;

import ch.qos.logback.classic.Level;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RequestLoggingFilterTest {
    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 302, 400, 401, 403, 404, 500})
    void logsOneCorrelatedCompletionWithSafeRouteAndCorrectLevel(int status) throws Exception {
        String requestId = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("GET", "/accounts/sensitive-id");
        request.setQueryString("token=synthetic-secret");
        request.addHeader(RequestLoggingFilter.HEADER, requestId);
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "previous");

        try (var capture = new LogCapture(RequestLoggingFilter.class)) {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).isEqualTo(requestId);
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/accounts/{id}");
                response.setStatus(status);
            });

            assertThat(response.getHeader(RequestLoggingFilter.HEADER)).isEqualTo(requestId);
            assertThat(capture.events()).hasSize(1);
            var event = capture.events().getFirst();
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", requestId);
            assertThat(event.getLevel()).isEqualTo(status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO);
            assertThat(LogCapture.fields(event)).containsEntry("status", status)
                    .containsEntry("route", "/accounts/{id}").containsKey("durationMs");
            assertThat(LogCapture.fields(event).toString()).doesNotContain("sensitive-id", "synthetic-secret");
            assertThat(MDC.get("requestId")).isEqualTo("previous");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "1-1-1-1-1", "\r\nsynthetic-secret", "00000000-0000-0000-0000-000000000001extra"})
    void replacesInvalidIdsAndDoesNotLeakContextToTheNextRequest(String invalid) throws Exception {
        var request = new MockHttpServletRequest("GET", "/sensitive-unmatched-url");
        request.addHeader(RequestLoggingFilter.HEADER, invalid);
        var response = new MockHttpServletResponse();
        try (var capture = new LogCapture(RequestLoggingFilter.class)) {
            filter.doFilter(request, response, (req, res) -> response.setStatus(404));
            String generated = response.getHeader(RequestLoggingFilter.HEADER);
            assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            var next = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/"), next, (req, res) -> { });
            assertThat(next.getHeader(RequestLoggingFilter.HEADER)).isNotEqualTo(generated);
            assertThat(capture.events()).hasSize(2);
            assertThat(LogCapture.fields(capture.events().getFirst())).containsEntry("route", "UNMATCHED");
        }
    }

    @Test
    void escapedExceptionProducesOneSafeDiagnosticAndOneCompletionAndRestoresContext() {
        var request = new MockHttpServletRequest("GET", "/");
        var response = new MockHttpServletResponse();
        try (var requests = new LogCapture(RequestLoggingFilter.class);
             var errors = new LogCapture(OperationalLog.class)) {
            assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                throw new ServletException("synthetic-secret", new IllegalStateException("private-payload"));
            })).isInstanceOf(ServletException.class);
            assertThat(requests.events()).hasSize(1);
            assertThat(LogCapture.fields(requests.events().getFirst())).containsEntry("status", 500);
            assertThat(errors.events()).hasSize(1);
            assertThat(LogCapture.fields(errors.events().getFirst()).toString())
                    .contains("ServletException", "IllegalStateException", "RequestLoggingFilterTest")
                    .doesNotContain("synthetic-secret", "private-payload");
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }
}
