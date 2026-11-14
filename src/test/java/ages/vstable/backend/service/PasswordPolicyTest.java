package ages.vstable.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Senha@123", "abcdefg1!", "12345678@", "senha com espaco 1"})
    void isStrong_passwordWithMinimumLengthDigitAndSpecialCharacter_isAccepted(String password) {
        // Act / Assert
        assertThat(PasswordPolicy.isStrong(password)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Ab1@", "Abc@1234\n", "Abc@1234\r", "Abc@1234 ", "SenhaSemNumero@",
            "SenhaSemEspecial1", "Senha@١٢٣"})
    void isStrong_passwordOutsideThePolicy_isRejected(String password) {
        // Act / Assert
        assertThat(PasswordPolicy.isStrong(password)).isFalse();
    }

    @Test
    void isStrong_exactly72Utf8Bytes_isAccepted() {
        // Arrange: 2 bytes ASCII + 35 "é" (2 bytes cada) = 72 bytes
        String password = "1@" + "é".repeat(35);

        // Act / Assert
        assertThat(PasswordPolicy.isStrong(password)).isTrue();
    }

    @Test
    void bcrypt_refusesPasswordsAbove72Bytes_soNoExistingAccountCanHaveOne() {
        // Arrange: antes da política, o onboarding chegava ao BCrypt com a senha longa
        String password = "1@" + "é".repeat(36);

        // Act / Assert: o encoder recusava (400 INVALID_ARGUMENT); agora a política responde 422 antes
        assertThatThrownBy(() -> new BCryptPasswordEncoder().encode(password))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isStrong_moreThan72Utf8Bytes_isRejectedEvenWithFewCharacters() {
        // Arrange: 38 caracteres, mas 74 bytes em UTF-8
        String password = "1@" + "é".repeat(36);

        // Act / Assert
        assertThat(password.length()).isLessThan(72);
        assertThat(PasswordPolicy.isStrong(password)).isFalse();
    }
}
