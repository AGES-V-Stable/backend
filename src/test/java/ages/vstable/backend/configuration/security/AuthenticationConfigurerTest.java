package ages.vstable.backend.configuration.security;

import ages.vstable.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
    void shouldReturnAuthenticationManager() {
        when(authenticationConfiguration.getAuthenticationManager())
                .thenReturn(authenticationManager);

        AuthenticationManager result =
                configurer.authenticationManager(authenticationConfiguration);

        assertSame(authenticationManager, result);

        verify(authenticationConfiguration)
                .getAuthenticationManager();
    }

    @Test
    void shouldCreateSecurityContextRepository() {
        SecurityContextRepository repository =
                configurer.securityContextRepository();

        assertNotNull(repository);
        assertInstanceOf(
                HttpSessionSecurityContextRepository.class,
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