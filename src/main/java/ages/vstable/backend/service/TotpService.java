package ages.vstable.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;

@Service
public class TotpService {

    private static final String ISSUER = "V-Stable";
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int SECRET_SIZE_BYTES = 20; // 160 bits (RFC 6238 / RFC 4226)
    private static final int DIGITS = 6;
    private static final int DIGITS_POWER = 1_000_000; // 10^6
    private static final long TIME_STEP_SECONDS = 30L;
    private static final int WINDOW_TOLERANCE = 1; // ±1 janela de 30s
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    @Autowired
    public TotpService() {
        this(Clock.systemUTC());
    }

    // Construtor visível para testes unitários determinísticos (controle de tempo/expiração)
    public TotpService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Gera um segredo aleatório seguro de 20 bytes (160 bits) codificado em Base32 (32 caracteres).
     */
    public String generateSecret() {
        byte[] randomBytes = new byte[SECRET_SIZE_BYTES];
        secureRandom.nextBytes(randomBytes);
        return encodeBase32(randomBytes);
    }

    /**
     * Monta a URI otpauth:// padronizada para Google Authenticator, Microsoft Authenticator e Authy.
     * Formato: otpauth://totp/V-Stable:{email}?secret={BASE32_SECRET}&issuer=V-Stable&algorithm=SHA1&digits=6&period=30
     */
    public String buildOtpAuthUri(String email, String base32Secret) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required to build otpauth URI");
        }
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new IllegalArgumentException("Secret is required to build otpauth URI");
        }
        String encodedEmail = URLEncoder.encode(email.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                ISSUER, encodedEmail, base32Secret.trim(), ISSUER, DIGITS, TIME_STEP_SECONDS
        );
    }

    /**
     * Valida o código TOTP de 6 dígitos considerando a janela atual de 30s e ±1 janela de tolerância.
     */
    public boolean verifyCode(String base32Secret, String code) {
        if (base32Secret == null || base32Secret.isBlank() || code == null) {
            return false;
        }
        String normalizedCode = code.trim();
        if (!normalizedCode.matches("^\\d{6}$")) {
            return false;
        }

        byte[] keyBytes;
        try {
            keyBytes = decodeBase32(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long currentWindow = Math.floorDiv(clock.instant().getEpochSecond(), TIME_STEP_SECONDS);

        for (int offset = -WINDOW_TOLERANCE; offset <= WINDOW_TOLERANCE; offset++) {
            String candidate = generateCodeForWindow(keyBytes, currentWindow + offset);
            if (constantTimeEquals(candidate, normalizedCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gera o código de 6 dígitos para uma janela de tempo específica (útil também em testes).
     */
    String generateCodeForWindow(byte[] keyBytes, long timeWindow) {
        try {
            byte[] counterBytes = ByteBuffer.allocate(8).putLong(timeWindow).array();
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(counterBytes);

            // Dynamic truncation (RFC 4226 Section 5.4)
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % DIGITS_POWER;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate TOTP code", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    static String encodeBase32(byte[] data) {
        StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                result.append(BASE32_ALPHABET.charAt(index));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            result.append(BASE32_ALPHABET.charAt(index));
        }
        return result.toString();
    }

    static byte[] decodeBase32(String base32) {
        String cleaned = base32.trim().replace("=", "").replace(" ", "").toUpperCase();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty Base32 secret");
        }
        byte[] output = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int outIndex = 0;

        for (int i = 0; i < cleaned.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(cleaned.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output[outIndex++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return output;
    }
}