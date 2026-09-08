package ages.vstable.backend.configuration.security;


import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
import ages.vstable.backend.service.UsuarioService;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import static org.apache.logging.log4j.util.Strings.isEmpty;

@Component
public class JwtTokenFilter extends OncePerRequestFilter {

    private final SecurityContextRepository securityContextRepository;
    private final JwtTokenUtils jwtTokenUtils;
    private final UsuarioService userService;

    public JwtTokenFilter(
            SecurityContextRepository securityContextRepository,
            JwtTokenUtils jwtTokenUtils,
            @Lazy UsuarioService userService) {
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

        // Get jwt token and validate
        final String token = headerAuthorization.split(" ")[1].trim();

        // Get user identity and set it on the spring security context
        UsuarioEntity user = userService
                .getByEmail(jwtTokenUtils.getUsernameFromToken(token));

        if (!jwtTokenUtils.validateToken(token, user)) {
            chain.doFilter(httpServletRequest, httpServletResponse);
            return;
        }
        httpServletRequest.setAttribute("user_id", jwtTokenUtils.getUsernameFromToken(token));
        List<SimpleGrantedAuthority> role = jwtTokenUtils.getClaimFromToken(token, JwtTokenFilter::apply);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user, null,
                        role == null ?
                                List.of() : role
                );

        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(httpServletRequest)
        );

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();

        securityContext.setAuthentication(authentication);
        securityContextRepository.saveContext(securityContext, httpServletRequest, httpServletResponse);

        chain.doFilter(httpServletRequest, httpServletResponse);
    }

    private static List<SimpleGrantedAuthority> apply(Claims claims) {
        Object claimedRoles = claims.get("role");
        if (claimedRoles instanceof List<?> roles) {
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority((String) role))
                    .toList();
        }
        return null;
    }
}
