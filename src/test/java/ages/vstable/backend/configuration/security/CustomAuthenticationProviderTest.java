package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomAuthenticationProviderTest {

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserDetails userDetails;

    private CustomAuthenticationProvider authenticationProvider;

    @BeforeEach
    void setUp() {
        authenticationProvider = new CustomAuthenticationProvider(
                userDetailsService,
                passwordEncoder
        );
    }

    @Test
    void authenticate_shouldReturnAuthenticatedToken_whenCredentialsAreValid() {
        String username = "user@example.com";
        String password = "password";
        String encodedPassword = "encoded-password";

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(username, password);

        when(userDetailsService.loadUserByUsername(username))
                .thenReturn(userDetails);

        when(userDetails.getPassword())
                .thenReturn(encodedPassword);

        when(passwordEncoder.matches(password, encodedPassword))
                .thenReturn(true);

        Authentication result =
                authenticationProvider.authenticate(authentication);

        assertNotNull(result);
        assertInstanceOf(UsernamePasswordAuthenticationToken.class, result);

        assertSame(userDetails, result.getPrincipal());
        assertEquals(password, result.getCredentials());
        assertEquals(userDetails.getAuthorities(), result.getAuthorities());

        verify(userDetailsService).loadUserByUsername(username);
        verify(passwordEncoder).matches(password, encodedPassword);
    }

    @Test
    void authenticate_shouldThrowAuthenticationException_whenPasswordIsInvalid() {
        String username = "user@example.com";
        String password = "wrong-password";
        String encodedPassword = "encoded-password";

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(username, password);

        when(userDetailsService.loadUserByUsername(username))
                .thenReturn(userDetails);

        when(userDetails.getPassword())
                .thenReturn(encodedPassword);

        when(passwordEncoder.matches(password, encodedPassword))
                .thenReturn(false);

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> authenticationProvider.authenticate(authentication)
        );

        assertEquals("Invalid credentials", exception.getMessage());

        verify(userDetailsService).loadUserByUsername(username);
        verify(passwordEncoder).matches(password, encodedPassword);
    }

    @Test
    void authenticate_shouldLoadUserUsingAuthenticationName() {
        String username = "user@example.com";
        String password = "password";

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(username, password);

        when(userDetailsService.loadUserByUsername(username))
                .thenReturn(userDetails);

        when(userDetails.getPassword())
                .thenReturn("encoded-password");

        when(passwordEncoder.matches(password, "encoded-password"))
                .thenReturn(true);

        authenticationProvider.authenticate(authentication);

        verify(userDetailsService).loadUserByUsername(username);
    }

    @Test
    void authenticate_shouldThrowNullPointerException_whenCredentialsAreNull() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("user@example.com", null);

        assertThrows(
                NullPointerException.class,
                () -> authenticationProvider.authenticate(authentication)
        );

        verifyNoInteractions(userDetailsService);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void supports_shouldReturnTrue_forUsernamePasswordAuthenticationToken() {
        assertTrue(
                authenticationProvider.supports(
                        UsernamePasswordAuthenticationToken.class
                )
        );
    }

    @Test
    void supports_shouldReturnFalse_forUnsupportedAuthenticationType() {
        assertFalse(
                authenticationProvider.supports(
                        Authentication.class
                )
        );
    }
}
