package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class BeanManagerTest {

    private final BeanManager beanManager = new BeanManager();

    @Test
    void passwordEncoder_shouldReturnBCryptPasswordEncoder() {
        PasswordEncoder encoder = beanManager.passwordEncoder();

        assertNotNull(encoder);
        assertInstanceOf(BCryptPasswordEncoder.class, encoder);
    }

    @Test
    void passwordEncoder_shouldEncodePassword() {
        PasswordEncoder encoder = beanManager.passwordEncoder();

        String password = "my-password";
        String encodedPassword = encoder.encode(password);

        assertNotEquals(password, encodedPassword);
        assertTrue(encoder.matches(password, encodedPassword));
    }

    @Test
    void passwordEncoder_shouldNotMatchWrongPassword() {
        PasswordEncoder encoder = beanManager.passwordEncoder();

        String encodedPassword = encoder.encode("correct-password");

        assertFalse(
                encoder.matches("wrong-password", encodedPassword)
        );
    }
}