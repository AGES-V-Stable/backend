package ages.vstable.backend.service;

import java.nio.charset.StandardCharsets;

/**
 * Política de senha das contas de acesso, igual no frontend e no backend: no mínimo 8 caracteres, um dígito
 * ASCII e um caractere especial, sem quebra de linha e com até 72 bytes em UTF-8 (limite do BCrypt, que
 * ignoraria ou recusaria o excedente).
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean isStrong(String password) {
        if (password == null || password.length() < MIN_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return false;
        }

        boolean hasNumber = false;
        boolean hasSpecialCharacter = false;
        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (character == '\n' || character == '\r' || character == '\u0085'
                    || character == '\u2028' || character == '\u2029') {
                return false;
            }
            hasNumber |= character >= '0' && character <= '9';
            hasSpecialCharacter |= !((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9'));
        }
        return hasNumber && hasSpecialCharacter;
    }
}
