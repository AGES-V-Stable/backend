package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationRateLimitFilterTest {

    private final InvitationAttemptLimiter limiter = mock(InvitationAttemptLimiter.class);
    private final InvitationRateLimitFilter filter = new InvitationRateLimitFilter(limiter, new ClientIpResolver(1));

    @ParameterizedTest
    @ValueSource(strings = {"/v1/company-invitations/resolve", "/v1/company-invitations/accept-new-user",
            "/v1/company-invitations/accept"})
    void doFilter_limitedRouteWithinTheLimit_continuesTheChainCountingTheClient(String path) throws Exception {
        // Arrange
        MockHttpServletRequest request = post(path);
        when(limiter.tryAcquire("203.0.113.7")).thenReturn(0L);
        MockFilterChain chain = new MockFilterChain();

        // Act
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // Assert
        assertThat(chain.getRequest()).isSameAs(request);
        verify(limiter).tryAcquire("203.0.113.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/company-invitations/%72esolve", "/v1/company-invitations/accept-%6Eew-user",
            "/v1/company-invitations/%61ccept", "/v1/company-invitations/resolve;x=1"})
    void doFilter_encodedOrParameterizedVariantOfALimitedRoute_isAlsoCounted(String path) throws Exception {
        // Arrange: o Spring roteia pelo caminho decodificado, então essas variações chegam ao controller.
        MockHttpServletRequest request = post(path);
        when(limiter.tryAcquire("203.0.113.7")).thenReturn(17L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Act
        filter.doFilter(request, response, new MockFilterChain());

        // Assert
        assertThat(response.getStatus()).isEqualTo(429);
        verify(limiter).tryAcquire("203.0.113.7");
    }

    @Test
    void doFilter_behindTheLambdaAdapter_countsTheClientResolvedFromTheTrustedHop() throws Exception {
        // Arrange
        MockHttpServletRequest request = post("/v1/company-invitations/resolve");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.44");

        // Act
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Assert
        verify(limiter).tryAcquire("198.51.100.44");
    }

    @Test
    void doFilter_limitExceeded_returns429RateLimitedWithRetryAfterWithoutReachingTheController() throws Exception {
        // Arrange
        when(limiter.tryAcquire("203.0.113.7")).thenReturn(17L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Act
        filter.doFilter(post("/v1/company-invitations/accept"), response, chain);

        // Assert
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_otherRoutesAndMethods_areNotCounted() throws Exception {
        // Arrange
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/v1/company-invitations/accept");
        MockHttpServletRequest login = post("/v1/auth/login");
        MockHttpServletRequest owner = post("/v1/companies/1/invitations");

        // Act
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{preflight, login, owner}) {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        // Assert
        verify(limiter, never()).tryAcquire(anyString());
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.7");
        return request;
    }
}
