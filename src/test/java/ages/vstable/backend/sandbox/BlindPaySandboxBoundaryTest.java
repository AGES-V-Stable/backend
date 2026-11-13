package ages.vstable.backend.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlindPaySandboxBoundaryTest {
    @Test
    void rejectsOtherOriginBeforeTransport() {
        var guard = new BlindPaySandboxBoundary("in_synthetic");
        var calls = new AtomicInteger();
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://example.test/v1/instances/in_synthetic"));
        assertThatThrownBy(() -> guard.intercept(request, new byte[0], (r, b) -> {
            calls.incrementAndGet(); return null;
        })).isInstanceOf(IOException.class);
        assertThat(calls.get()).isZero();
        assertThat(guard.stopReason()).isEqualTo("OUTBOUND_SCOPE_DENIED");
    }

    @Test
    void rejectsWritesUntilDevelopmentIsConfirmed() {
        var guard = new BlindPaySandboxBoundary("in_synthetic");
        var request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.blindpay.com/v1/instances/in_synthetic/quotes"));
        assertThatThrownBy(() -> guard.intercept(request, new byte[0], (r, b) -> null)).isInstanceOf(IOException.class);
        assertThat(guard.count()).isZero();
        assertThat(guard.stopReason()).isEqualTo("DEVELOPMENT_NOT_CONFIRMED");
    }

    @Test
    void buffersResponseButExportsOnlyAllowlistedMetadataAndStopsOnRateLimit() throws Exception {
        var guard = new BlindPaySandboxBoundary("in_synthetic");
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.blindpay.com/v1/instances/in_synthetic"));
        request.getHeaders().setBearerAuth("synthetic-credential");
        String body = "{\"email\":\"private@example.test\",\"id\":\"in_synthetic\",\"code\":\"AUTH_RATE_LIMITED\"}";
        var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var buffered = guard.intercept(request, new byte[0], (r, b) -> response);
        assertThat(new String(buffered.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        String exported = JsonMapper.builder().build().writeValueAsString(guard.calls());
        assertThat(exported).doesNotContain("private@example.test", "in_synthetic", "synthetic-credential");
        assertThat(guard.stopReason()).isEqualTo("PROVIDER_HTTP_429");
        assertThatThrownBy(() -> guard.intercept(request, new byte[0], (r, b) -> null)).isInstanceOf(IOException.class);
        assertThat(guard.count()).isEqualTo(1);
    }
}
