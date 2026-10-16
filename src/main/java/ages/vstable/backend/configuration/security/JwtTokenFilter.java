package ages.vstable.backend.configuration.security;


import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.apache.logging.log4j.util.Strings.isEmpty;

@Slf4j
@Component
public class JwtTokenFilter extends OncePerRequestFilter {

    /** Request attribute read by the authentication entry point to explain a 401. */
    public static final String AUTH_ERROR_ATTRIBUTE = "vstable.auth.error";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    private final SecurityContextRepository securityContextRepository;
    private final JwtTokenUtils jwtTokenUtils;
    private final UserService userService;

    public JwtTokenFilter(
            SecurityContextRepository securityContextRepository,
            JwtTokenUtils jwtTokenUtils,
            @Lazy UserService userService) {
        this.securityContextRepository = securityContextRepository;
        this.jwtTokenUtils = jwtTokenUtils;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest,
                                    HttpServletResponse httpServletResponse,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Get authorization header and validate
        final String headerAuthorization = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (isEmpty(headerAuthorization) || !headerAuthorization.startsWith("Bearer ")
        ) {
            chain.doFilter(httpServletRequest, httpServletResponse);
            return;
        }

        // Get jwt token and validate. An invalid token never authenticates the request:
        // public routes keep working and protected routes get a 401 from the entry point.
        final String token = headerAuthorization.substring("Bearer ".length()).trim();

        try {
            String email = jwtTokenUtils.getUsernameFromToken(token);
            UserDetails principal = userService.loadPrincipal(email, jwtTokenUtils.getAccountTypeFromToken(token));

            if (principal.isEnabled() && jwtTokenUtils.validateToken(token, principal)) {
                authenticate(principal, email, httpServletRequest, httpServletResponse);
            } else {
                rejectToken(httpServletRequest, TOKEN_INVALID);
            }
        } catch (ExpiredJwtException ex) {
            rejectToken(httpServletRequest, TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException ex) {
            log.debug("Rejected bearer token: {}", ex.getMessage());
            rejectToken(httpServletRequest, TOKEN_INVALID);
        }

        chain.doFilter(httpServletRequest, httpServletResponse);
    }

    private void authenticate(UserDetails principal,
                              String email,
                              HttpServletRequest httpServletRequest,
                              HttpServletResponse httpServletResponse) {
        httpServletRequest.setAttribute("user_id", email);

        // Authorities come from the stored account, not from the token claims, so a
        // revoked role takes effect without waiting for the token to expire.
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(httpServletRequest)
        );

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();

        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, httpServletRequest, httpServletResponse);
    }

    private void rejectToken(HttpServletRequest httpServletRequest, String reason) {
        SecurityContextHolder.clearContext();
        httpServletRequest.setAttribute(AUTH_ERROR_ATTRIBUTE, reason);
    }
}
