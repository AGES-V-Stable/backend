package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.service.UsuarioService;
import ages.vstable.backend.utils.JwtTokenUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtTokenFilterTest {

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private JwtTokenUtils jwtTokenUtils;

    @Mock
    private UsuarioService userService;

    @Mock
    private FilterChain filterChain;

    @Mock
    private UsuarioEntity user;

    private JwtTokenFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtTokenFilter(
                securityContextRepository,
                jwtTokenUtils,
                userService
        );

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldContinueWhenAuthorizationHeaderIsMissing() throws Exception {
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenUtils, userService);
        verifyNoInteractions(securityContextRepository);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldContinueWhenAuthorizationHeaderIsNotBearer() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic abc123");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenUtils, userService);
        verifyNoInteractions(securityContextRepository);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldAuthenticateUserWithValidToken() throws Exception {
        String token = "valid-token";
        String email = "user@example.com";

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token
        );

        when(jwtTokenUtils.getUsernameFromToken(token))
                .thenReturn(email);

        when(userService.getByEmail(email))
                .thenReturn(user);

        when(jwtTokenUtils.validateToken(token, user))
                .thenReturn(true);

        when(jwtTokenUtils.getClaimFromToken(
                eq(token),
                any()
        )).thenReturn(List.of(
                new SimpleGrantedAuthority("ROLE_USER")
        ));

        filter.doFilter(request, response, filterChain);

        verify(userService).getByEmail(email);
        verify(jwtTokenUtils).validateToken(token, user);
        verify(filterChain).doFilter(request, response);

        verify(securityContextRepository).saveContext(
                any(SecurityContext.class),
                eq(request),
                eq(response)
        );

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        assertNotNull(authentication);
        assertInstanceOf(
                UsernamePasswordAuthenticationToken.class,
                authentication
        );

        assertSame(user, authentication.getPrincipal());

        assertTrue(
                authentication.getAuthorities()
                        .contains(new SimpleGrantedAuthority("ROLE_USER"))
        );

        assertEquals(
                email,
                request.getAttribute("user_id")
        );
    }

    @Test
    void shouldContinueWhenTokenIsInvalid() throws Exception {
        String token = "invalid-token";
        String email = "user@example.com";

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token
        );

        when(jwtTokenUtils.getUsernameFromToken(token))
                .thenReturn(email);

        when(userService.getByEmail(email))
                .thenReturn(user);

        when(jwtTokenUtils.validateToken(token, user))
                .thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(userService).getByEmail(email);
        verify(jwtTokenUtils).validateToken(token, user);

        verify(filterChain).doFilter(request, response);

        verify(securityContextRepository, never())
                .saveContext(any(), any(), any());

        assertNull(SecurityContextHolder.getContext().getAuthentication());

        assertNull(request.getAttribute("user_id"));
    }

    @Test
    void shouldCreateAuthenticationWithMultipleRoles() throws Exception {
        String token = "valid-token";
        String email = "user@example.com";

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token
        );

        when(jwtTokenUtils.getUsernameFromToken(token))
                .thenReturn(email);

        when(userService.getByEmail(email))
                .thenReturn(user);

        when(jwtTokenUtils.validateToken(token, user))
                .thenReturn(true);

        when(jwtTokenUtils.getClaimFromToken(
                eq(token),
                any()
        )).thenReturn(List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
        ));

        filter.doFilter(request, response, filterChain);

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        assert authentication != null;
        assertEquals(2, authentication.getAuthorities().size());

        assertTrue(authentication.getAuthorities().contains(
                new SimpleGrantedAuthority("ROLE_USER")
        ));

        assertTrue(authentication.getAuthorities().contains(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        ));
    }

    @Test
    void shouldAuthenticateWithoutAuthoritiesWhenTokenHasNoRoles() throws Exception {
        String token = "valid-token";
        String email = "user@example.com";

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token
        );

        when(jwtTokenUtils.getUsernameFromToken(token))
                .thenReturn(email);

        when(userService.getByEmail(email))
                .thenReturn(user);

        when(jwtTokenUtils.validateToken(token, user))
                .thenReturn(true);

        when(jwtTokenUtils.getClaimFromToken(
                eq(token),
                any()
        )).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().isEmpty());
    }
}
