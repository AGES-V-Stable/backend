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
        ReflectionTestUtils.setField(configuration, "allowedOrigins", "https://app.vstable.test, , http://localhost:*");
        cors = configuration.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/v1/companies"));
    }

    @Test
    void origins_onlyTheConfiguredOnesAreAllowedAndBlankEntriesAreFilteredOut() {
        // Act / Assert
        assertThat(cors.getAllowedOriginPatterns())
                .containsExactly("https://app.vstable.test", "http://localhost:*");
        assertThat(cors.getAllowCredentials()).isTrue();
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

    @Test
    void writeError_setsUtf8CharacterEncodingStatusAndJsonBody() throws Exception {
        jakarta.servlet.http.HttpServletResponse response =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        java.io.StringWriter body = new java.io.StringWriter();
        org.mockito.Mockito.when(response.getWriter()).thenReturn(new java.io.PrintWriter(body));

        ReflectionTestUtils.invokeMethod(
                SecurityConfiguration.class,
                "writeError",
                response,
                jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                "Access denied"
        );

        org.mockito.Mockito.verify(response).setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
        org.mockito.Mockito.verify(response).setContentType("application/json");
        org.mockito.Mockito.verify(response).setCharacterEncoding("UTF-8");
        assertThat(body.toString()).isEqualTo("{\"message\":\"Access denied\",\"code\":\"ACCESS_DENIED\"}");
    }

    @Test
    void jwtTokenFilterRegistration_disablesServletContainerAutoRegistration() {
        SecurityConfiguration configuration = new SecurityConfiguration(null, null, null, null);
        JwtTokenFilter filter = org.mockito.Mockito.mock(JwtTokenFilter.class);

        var registration = configuration.jwtTokenFilterRegistration(filter);

        assertThat(registration).isNotNull();
        assertThat(registration.isEnabled()).isFalse();
        assertThat(registration.getFilter()).isSameAs(filter);

        InternalJobSecurityConfiguration internalJobConfig = new InternalJobSecurityConfiguration();
        var internalRegistration = internalJobConfig.jwtTokenFilterRegistration(filter);
        assertThat(internalRegistration).isNotNull();
        assertThat(internalRegistration.isEnabled()).isFalse();
        assertThat(internalRegistration.getFilter()).isSameAs(filter);
    }

    @Test
    void quoteConfigurationAndLambdaPostProcessor_exposeExpectedPoolSettingsAndOrder() {
        ages.vstable.backend.configuration.QuoteConfiguration quoteConfig =
                new ages.vstable.backend.configuration.QuoteConfiguration();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) quoteConfig.quoteProviderExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getQueueCapacity()).isEqualTo(50);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("quote-provider-");
        } finally {
            executor.shutdown();
        }

        ages.vstable.backend.configuration.LambdaSecretsEnvironmentPostProcessor postProcessor =
                new ages.vstable.backend.configuration.LambdaSecretsEnvironmentPostProcessor();
        assertThat(postProcessor.getOrder())
                .isEqualTo(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }
}
