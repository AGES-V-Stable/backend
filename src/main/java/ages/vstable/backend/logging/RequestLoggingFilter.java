package ages.vstable.backend.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String COMPLETED = RequestLoggingFilter.class.getName() + ".completed";
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && UUID_PATTERN.matcher(supplied).matches()
                ? UUID.fromString(supplied).toString() : UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        boolean escaped = false;
        try {
            MDC.put("requestId", requestId);
            response.setHeader(HEADER, requestId);
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            escaped = true;
            OperationalLog.unexpected(ex);
            throw ex;
        } finally {
            try {
                if (request.getAttribute(COMPLETED) == null) {
                    request.setAttribute(COMPLETED, true);
                    int status = escaped ? 500 : response.getStatus();
                    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    // Method names are client input too; keep a fixed fallback for extension methods.
                    String method = switch (request.getMethod()) {
                        case "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "TRACE" -> request.getMethod();
                        default -> "OTHER";
                    };
                    LOG.atLevel(status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO)
                            .addKeyValue("event", "http.request.completed").addKeyValue("operation", "http.request")
                            .addKeyValue("method", method).addKeyValue("route", route == null ? "UNMATCHED" : route.toString())
                            .addKeyValue("status", status).addKeyValue("outcome", status >= 400 ? "failure" : "success")
                            .addKeyValue("durationMs", OperationalLog.elapsedMillis(startedAt))
                            .log("HTTP request completed");
                }
            } finally {
                LogContext.restore(previous);
            }
        }
    }
}
