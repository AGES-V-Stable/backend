package ages.vstable.backend.configuration.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.EnableGlobalAuthentication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableGlobalAuthentication
public class SecurityConfiguration {

    private final JwtTokenFilter jwtTokenFilter;

    private final SecurityContextRepository securityContextRepository;
    private final AuthenticationProvider authenticationProvider;

    /**
     * Comma-separated list of allowed frontend origins. Patterns such as
     * "http://localhost:*" or "https://*.example.com" are accepted.
     */
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    private static final String[] SWAGGER_PERMIT_LIST = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/swagger-resource/**"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http
    ) throws Exception {
        // Enable CORS (using the corsConfigurationSource bean below) and disable CSRF
        http
                .cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)

                // Bearer tokens only: no HTTP session is created or read
                .sessionManagement(httpSecuritySessionManagementConfigurer -> httpSecuritySessionManagementConfigurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(securityContext -> securityContext.securityContextRepository(securityContextRepository))

                // Set permissions on endpoints. Admin/owner rules are enforced per endpoint
                // with @PreAuthorize and CompanyAccess.
                .authorizeHttpRequests(authorizationManagerRequestMatcherRegistry -> authorizationManagerRequestMatcherRegistry
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/onboarding").permitAll()
                        .requestMatchers(HttpMethod.GET, SWAGGER_PERMIT_LIST).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )

                // Add JWT token filter
                .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authenticationProvider(authenticationProvider)

                // Same JSON error shape ({message, code}) as GlobalExceptionHandler
                .exceptionHandling(httpSecurityExceptionHandlingConfigurer -> httpSecurityExceptionHandlingConfigurer
                        .authenticationEntryPoint((request, response, authException) ->
                                writeUnauthorized(request, response))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access denied")));

        return http.build();
    }

    /**
     * JwtTokenFilter is a @Component so it can be injected above; without this, Spring Boot would
     * also register it as a plain servlet filter outside the security chain.
     */
    @Bean
    public FilterRegistrationBean<JwtTokenFilter> jwtTokenFilterRegistration(JwtTokenFilter filter) {
        FilterRegistrationBean<JwtTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Single CORS policy for the whole API, configured through CORS_ALLOWED_ORIGINS. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT, "X-Requested-With"));
        // The login token is returned in the Authorization response header
        config.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object reason = request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE);
        if (JwtTokenFilter.TOKEN_EXPIRED.equals(reason)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, JwtTokenFilter.TOKEN_EXPIRED, "Session expired");
        } else if (JwtTokenFilter.TOKEN_INVALID.equals(reason)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, JwtTokenFilter.TOKEN_INVALID, "Invalid token");
        } else {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Messages and codes are fixed constants, so plain formatting is safe here.
        response.getWriter().write("{\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
