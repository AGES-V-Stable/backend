package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenUtils jwtTokenUtil;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication authentication;

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
        String token = "jwt-token";

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(jwtTokenUtil.generateToken(user)).thenReturn(token);

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer " + token, response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldReturnUnauthorizedWhenUserDoesNotExist() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenCredentialsAreInvalid() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnLockedWhenUserIsLocked() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new LockedException("User is locked"));

        ResponseEntity<?> response = authController.getPermissions(request);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals(Map.of("message", "User is locked"), response.getBody());

        verify(userRepository).findByEmail("user@email.com");

        verify(authenticationManager).authenticate(any(
                UsernamePasswordAuthenticationToken.class));

        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldAuthenticateWithProvidedCredentials() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(jwtTokenUtil.generateToken(user)).thenReturn("jwt-token");

        authController.getPermissions(request);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor = ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        UsernamePasswordAuthenticationToken authToken = captor.getValue();
        assertEquals("user@email.com", authToken.getPrincipal());
        assertEquals("password", authToken.getCredentials());
    }
}
