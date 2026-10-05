package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtTokenFilterTest {

    private static final String TOKEN = "token";
    private static final String EMAIL = "user@example.com";

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private JwtTokenUtils jwtTokenUtils;

    @Mock
    private UserService userService;

    @Mock
    private FilterChain filterChain;

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

    private void withBearerToken() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    private UserEntity companyUser() {
        return UserEntity.builder().email(EMAIL).build();
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
    void shouldAuthenticateCompanyUserWithAuthoritiesFromTheStoredAccount() throws Exception {
        withBearerToken();
        UserEntity user = companyUser();

        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenReturn(EMAIL);
        when(jwtTokenUtils.getAccountTypeFromToken(TOKEN)).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal(EMAIL, JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        when(jwtTokenUtils.validateToken(TOKEN, user)).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(securityContextRepository).saveContext(any(SecurityContext.class), eq(request), eq(response));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertInstanceOf(UsernamePasswordAuthenticationToken.class, authentication);
        assertSame(user, authentication.getPrincipal());
        assertEquals(1, authentication.getAuthorities().size());
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
        assertEquals(EMAIL, request.getAttribute("user_id"));
    }

    @Test
    void shouldAuthenticateAdministratorWithAdminRole() throws Exception {
        withBearerToken();
        AdministratorEntity admin = AdministratorEntity.builder().email("admin@vstable.com").build();

        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenReturn("admin@vstable.com");
        when(jwtTokenUtils.getAccountTypeFromToken(TOKEN)).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_ADMIN);
        when(userService.loadPrincipal("admin@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_ADMIN)).thenReturn(admin);
        when(jwtTokenUtils.validateToken(TOKEN, admin)).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertSame(admin, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void shouldContinueUnauthenticatedWhenTokenIsInvalid() throws Exception {
        withBearerToken();
        UserEntity user = companyUser();

        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenReturn(EMAIL);
        when(jwtTokenUtils.getAccountTypeFromToken(TOKEN)).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal(EMAIL, JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        when(jwtTokenUtils.validateToken(TOKEN, user)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute("user_id"));
        assertEquals(JwtTokenFilter.TOKEN_INVALID, request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE));
    }

    @Test
    void shouldNotAuthenticateDisabledAccount() throws Exception {
        withBearerToken();
        UserEntity user = companyUser();
        user.setActive(false);

        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenReturn(EMAIL);
        when(jwtTokenUtils.getAccountTypeFromToken(TOKEN)).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal(EMAIL, JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldMarkExpiredTokenAndContinueWithoutThrowing() throws Exception {
        withBearerToken();
        when(jwtTokenUtils.getUsernameFromToken(TOKEN))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userService);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(JwtTokenFilter.TOKEN_EXPIRED, request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE));
    }

    @Test
    void shouldMarkMalformedTokenAndContinueWithoutThrowing() throws Exception {
        withBearerToken();
        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenThrow(new MalformedJwtException("bad"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(JwtTokenFilter.TOKEN_INVALID, request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE));
    }

    @Test
    void shouldMarkTokenOfDeletedAccountAsInvalid() throws Exception {
        withBearerToken();
        when(jwtTokenUtils.getUsernameFromToken(TOKEN)).thenReturn(EMAIL);
        when(jwtTokenUtils.getAccountTypeFromToken(TOKEN)).thenReturn(JwtTokenUtils.ACCOUNT_TYPE_USER);
        when(userService.loadPrincipal(EMAIL, JwtTokenUtils.ACCOUNT_TYPE_USER))
                .thenThrow(new UsernameNotFoundException("User not found"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(JwtTokenFilter.TOKEN_INVALID, request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE));
    }
}
