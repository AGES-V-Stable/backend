package ages.vstable.backend.utils;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Geração e hash do token do link de convite. O token é opaco e imprevisível (256 bits); o banco guarda
 * apenas o hash para validá-lo, e a cópia cifrada para o proprietário copiar o link fica em
 * {@link InvitationTokenCipher}.
 */
@Component
public class InvitationTokens {

    private static final int TOKEN_LENGTH_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Token aleatório de 32 bytes em base64url sem preenchimento (43 caracteres). */
    public String generate() {
        byte[] bytes = new byte[TOKEN_LENGTH_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 do token em hexadecimal minúsculo (64 caracteres), o valor gravado em {@code token_hash}. */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
