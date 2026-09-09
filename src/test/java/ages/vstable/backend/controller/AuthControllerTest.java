package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
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
import org.springframework.security.core.Authentication;

import java.util.Optional;

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

    @Mock
    private UsuarioRepository userRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private AuthController authController;

    private AuthRequestDTO request;
    private UsuarioEntity user;

    @BeforeEach
    void setUp() {
        request = new AuthRequestDTO();
        request.setEmail("user@email.com");
        request.setPassword("password");

        user = new UsuarioEntity();
        user.setEmail("user@email.com");
    }

    @Test
    void shouldLoginSuccessfully() {
        // Arrange
        String token = "jwt-token";

        when(userRepository.findByEmail("user@email.com"))
                .thenReturn(Optional.of(user));

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        when(jwtTokenUtil.generateToken(user))
                .thenReturn(token);

        // Act
        ResponseEntity<?> response = authController.getPermissions(request);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(token, response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));

        verify(userRepository).findByEmail("user@email.com");
        verify(authenticationManager).authenticate(any(
                UsernamePasswordAuthenticationToken.class));
        verify(jwtTokenUtil).generateToken(user);
    }

    @Test
    void shouldReturnUnauthorizedWhenUserDoesNotExist() {
        // Arrange
        when(userRepository.findByEmail("user@email.com"))
                .thenReturn(Optional.empty());

        // Act
        ResponseEntity<?> response = authController.getPermissions(request);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody());

        verify(userRepository).findByEmail("user@email.com");

        verifyNoInteractions(authenticationManager);
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnUnauthorizedWhenCredentialsAreInvalid() {
        // Arrange
        when(userRepository.findByEmail("user@email.com"))
                .thenReturn(Optional.of(user));

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        // Act
        ResponseEntity<?> response = authController.getPermissions(request);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(response.getBody());

        verify(userRepository).findByEmail("user@email.com");

        verify(authenticationManager).authenticate(any(
                UsernamePasswordAuthenticationToken.class));

        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnLockedWhenUserIsLocked() {
        // Arrange
        when(userRepository.findByEmail("user@email.com"))
                .thenReturn(Optional.of(user));

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new LockedException("User is locked"));

        // Act
        ResponseEntity<?> response = authController.getPermissions(request);

        // Assert
        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals("User is locked", response.getBody());

        verify(userRepository).findByEmail("user@email.com");

        verify(authenticationManager).authenticate(any(
                UsernamePasswordAuthenticationToken.class));

        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldAuthenticateWithProvidedCredentials() {
        // Arrange
        when(userRepository.findByEmail(request.getEmail()))
                .thenReturn(Optional.of(user));

        when(authenticationManager.authenticate(any()))
                .thenReturn(authentication);

        when(jwtTokenUtil.generateToken(user))
                .thenReturn("jwt-token");

        // Act
        authController.getPermissions(request);

        // Assert
        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);

        verify(authenticationManager).authenticate(captor.capture());

        UsernamePasswordAuthenticationToken authToken = captor.getValue();

        assertEquals("user@email.com", authToken.getPrincipal());
        assertEquals("password", authToken.getCredentials());
    }
}
