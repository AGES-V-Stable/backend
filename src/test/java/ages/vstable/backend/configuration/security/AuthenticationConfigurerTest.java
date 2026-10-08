package ages.vstable.backend.configuration.security;

import ages.vstable.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationConfigurerTest {

    @Mock
    private UserService userService;

    @Mock
    private AuthenticationConfiguration authenticationConfiguration;

    @Mock
    private AuthenticationManager authenticationManager;

    private AuthenticationConfigurer configurer;

    @BeforeEach
    void setUp() {
        configurer = new AuthenticationConfigurer(userService);
    }

    @Test
    void shouldCreateUserDetailsService() {
        UserDetailsService userDetailsService = configurer.userDetailsService();

        assertNotNull(userDetailsService);
    }


    @Test
    void shouldReturnAuthenticationManager() throws Exception {
        when(authenticationConfiguration.getAuthenticationManager())
                .thenReturn(authenticationManager);

        AuthenticationManager result =
                configurer.authenticationManager(authenticationConfiguration);

        assertSame(authenticationManager, result);

        verify(authenticationConfiguration)
                .getAuthenticationManager();
    }

    @Test
    void shouldCreateRequestScopedSecurityContextRepository() {
        SecurityContextRepository repository =
                configurer.securityContextRepository();

        assertNotNull(repository);
        assertInstanceOf(
                RequestAttributeSecurityContextRepository.class,
                repository
        );
    }

    @Test
    void shouldCreateRequestAttributeSecurityContextRepository() {
        RequestAttributeSecurityContextRepository repository =
                configurer.requestAttributeSecurityContextRepository();

        assertNotNull(repository);
    }
}
