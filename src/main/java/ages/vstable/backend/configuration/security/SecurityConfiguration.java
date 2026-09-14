package ages.vstable.backend.configuration.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
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
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;


import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableGlobalAuthentication
public class SecurityConfiguration {

    private final JwtTokenFilter jwtTokenFilter;

    private final RequestAttributeSecurityContextRepository requestAttributeSecurityContextRepository;
    private final SecurityContextRepository securityContextRepository;
    private final AuthenticationProvider authenticationProvider;

    @Value("${api.auth.header.name}")
    private String apiAuthHeaderName;

    private static final String[] SWAGGER_PERMIT_LIST = {
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resource/**"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http
    ) throws Exception {
        HeaderWriterLogoutHandler clearSiteData = new HeaderWriterLogoutHandler(new ClearSiteDataHeaderWriter(ClearSiteDataHeaderWriter.Directive.COOKIES));
        // Enable CORS and disable CSRF
        http
                .cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)

                // Set session management to stateless
                .sessionManagement(httpSecuritySessionManagementConfigurer -> httpSecuritySessionManagementConfigurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Set permissions on endpoints
                .authorizeHttpRequests(authorizationManagerRequestMatcherRegistry -> authorizationManagerRequestMatcherRegistry
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/cadastros/representante/acesso").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/exists").permitAll()
                        .requestMatchers(HttpMethod.GET, SWAGGER_PERMIT_LIST).permitAll()
                        .anyRequest().authenticated()
                )

                // Add JWT token filter
                .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class)
//                .addFilterAfter(requestHeaderAuthenticationFilter, HeaderWriterFilter.class)
                .authenticationProvider(authenticationProvider)
                .securityContext((securityContext) -> securityContext
                        .securityContextRepository(new DelegatingSecurityContextRepository(
                                requestAttributeSecurityContextRepository,
                                securityContextRepository
                        ))
                )
                // Set unauthorized requests exception handler.
                .exceptionHandling(httpSecurityExceptionHandlingConfigurer -> httpSecurityExceptionHandlingConfigurer
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))

               .logout((logout) -> logout.addLogoutHandler(clearSiteData));

        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        // Origens permitidas (dev e prod)
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:*",
                "http://127.0.0.1:*"
        ));
        // Métodos e headers liberados
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    public RequestHeaderAuthenticationFilter requestHeaderAuthenticationFilter(AuthenticationManager authenticationManager) {
        RequestHeaderAuthenticationFilter filter = new RequestHeaderAuthenticationFilter();
        filter.setAuthenticationManager(authenticationManager);
        filter.setPrincipalRequestHeader(apiAuthHeaderName);
        filter.setExceptionIfHeaderMissing(false);
        return filter;
    }


}
