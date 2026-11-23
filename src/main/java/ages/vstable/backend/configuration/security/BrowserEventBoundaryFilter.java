package ages.vstable.backend.configuration.security;

import ages.vstable.backend.service.observability.ObservabilityIdentity;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

/** Runs after security, before JSON deserialization; bounds actual bytes, including chunked requests. */
@Component
public class BrowserEventBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 1024;
    private final Map<UUID, Window> windows = new LinkedHashMap<>();
    private record Window(long expiresAt, int count) { }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults()
                .matcher(org.springframework.http.HttpMethod.POST, "/v1/observability/browser-events").matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = authentication == null ? null : ObservabilityIdentity.id(authentication.getPrincipal());
        if (actorId == null) { reject(response, 403, "ACCESS_DENIED"); return; }
        if (!allow(actorId, System.nanoTime())) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "RATE_LIMITED"); return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { reject(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        }, response);
    }

    public synchronized boolean allow(UUID actorId, long now) {
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Window current = windows.get(actorId);
        if (current == null && windows.size() >= 5000) return false;
        if (current != null && current.count() >= 20) return false;
        windows.put(actorId, new Window(current == null ? now + 60_000_000_000L : current.expiresAt(),
                current == null ? 1 : current.count() + 1));
        return true;
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Browser event rejected\"}");
    }
}
