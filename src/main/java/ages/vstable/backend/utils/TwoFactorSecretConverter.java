package ages.vstable.backend.utils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
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

@Converter
@Component 
public class TwoFactorSecretConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12; // 96 bits recomendados pelo NIST para GCM
    private static final int TAG_LENGTH_BITS = 128; // 16 bytes de tag de autenticação
    private static final int KEY_LENGTH_BYTES = 32; // 256 bits para AES-256

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TwoFactorSecretConverter(@Value("${api.auth.two-factor.encryption-key}") String base64Key){
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("A chave de criptografia 2FA deve ter exatamente 32 bytes (256 bits) após decodificação Base64.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override 
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte [IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] cipherTextWithTag = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherTextWithTag.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherTextWithTag);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar o segredo TOTP", e);
        }
    }

    @Override 
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData);
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

            byte[] plainText = cipher.doFinal(cipherTextWithTag);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao descriptografar o segredo TOTP", e);
        }
    }
}