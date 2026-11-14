package ages.vstable.backend.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra autenticada (AES-GCM) da cópia recuperável do token de convite. A chave vem da configuração,
 * nunca do banco, e cada cópia grava o identificador da chave que a cifrou. O token só é decifrado
 * para o proprietário copiar o link de novo; a validação do convite usa apenas o hash.
 */
@Component
public class InvitationTokenCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKey secretKey;
    private final String keyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public InvitationTokenCipher(
            @Value("${app.invitation.encryption-key}") String base64Key,
            @Value("${app.invitation.key-version}") String keyVersion) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "A chave de criptografia dos convites deve ter exatamente 32 bytes (256 bits) após decodificação Base64.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.keyVersion = keyVersion;
    }

    /** Identificador da chave que cifra as novas cópias. */
    public String keyVersion() {
        return keyVersion;
    }

    public String encrypt(String token) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherTextWithTag = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherTextWithTag.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherTextWithTag);
            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar o token do convite", e);
        }
    }

    /** Decifra uma cópia gravada com a chave de {@code ciphertextKeyVersion}; outra versão não é suportada. */
    public String decrypt(String ciphertext, String ciphertextKeyVersion) {
        if (!keyVersion.equals(ciphertextKeyVersion)) {
            throw new IllegalStateException("Versão de chave do convite não suportada");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            if (decoded.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Payload criptografado inválido");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byteBuffer.get(iv);
            byte[] cipherTextWithTag = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherTextWithTag);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherTextWithTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao descriptografar o token do convite", e);
        }
    }
}
