package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationCorsTest {

    private CorsConfiguration cors;

    @BeforeEach
    void setUp() {
        SecurityConfiguration configuration = new SecurityConfiguration(null, null, null, null);
        ReflectionTestUtils.setField(configuration, "allowedOrigins", "https://app.vstable.test, http://localhost:*");
        cors = configuration.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/v1/companies"));
    }

    @Test
    void origins_onlyTheConfiguredOnesAreAllowed() {
        // Act / Assert
        assertThat(cors.checkOrigin("https://app.vstable.test")).isEqualTo("https://app.vstable.test");
        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void requestHeaders_ifMatchIsAllowedForTheVersionCheckAndUnknownHeadersAreNot() {
        // Act / Assert
        assertThat(cors.checkHeaders(List.of("Authorization", "Content-Type", "If-Match"))).isNotNull();
        assertThat(cors.checkHeaders(List.of("X-Not-Allowed"))).isNull();
    }

    @Test
    void responseHeaders_etagAndRetryAfterAreReadableByTheFrontend() {
        // Act / Assert
        assertThat(cors.getExposedHeaders()).contains("ETag", "Retry-After", "Authorization");
    }

    @Test
    void methods_theMemberAndInvitationRoutesCanBeCalledFromTheFrontend() {
        // Act / Assert
        assertThat(cors.checkHttpMethod(HttpMethod.PATCH)).isNotNull();
        assertThat(cors.checkHttpMethod(HttpMethod.DELETE)).isNotNull();
        assertThat(cors.checkHttpMethod(HttpMethod.POST)).isNotNull();
        assertThat(cors.checkHttpMethod(HttpMethod.TRACE)).isNull();
    }
}
