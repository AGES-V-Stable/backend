package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenUtils jwtTokenUtil;

    @InjectMocks
    private AuthController authController;

    private AuthRequestDTO request;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        request = new AuthRequestDTO();
        request.setEmail("user@email.com");
        request.setPassword("password");

        user = new UserEntity();
        user.setEmail("user@email.com");
    }

    @Test
    void shouldLoginSuccessfully() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(jwtTokenUtil.generateToken(user)).thenReturn("jwt-token");

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer jwt-token", response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        verify(jwtTokenUtil).generateToken(user);
    }

    @Test
    void shouldIssueTokenForAdministrator() {
        AdministratorEntity admin = AdministratorEntity.builder().email("admin@vstable.com").build();
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(jwtTokenUtil.generateToken(admin)).thenReturn("admin-token");

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer admin-token", response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldReturnUnauthorizedWhenUserDoesNotExist() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new UsernameNotFoundException("User not found"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnUnauthorizedWhenCredentialsAreInvalid() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnLockedWhenUserIsLocked() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new LockedException("User is locked"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals(Map.of("message", "User is locked", "code", "ACCOUNT_LOCKED"), response.getBody());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldAuthenticateWithProvidedCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(jwtTokenUtil.generateToken(user)).thenReturn("jwt-token");

        authController.getPermissions(request);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertEquals("user@email.com", captor.getValue().getPrincipal());
        assertEquals("password", captor.getValue().getCredentials());
    }
}
