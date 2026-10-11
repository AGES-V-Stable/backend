package ages.vstable.backend.external.blindpay;

import ages.vstable.backend.exception.BlindPayIntegrationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Confere a assinatura dos webhooks da BlindPay (entregues via Svix).
 *
 * <p>Conteúdo assinado: {@code "{svix-id}.{svix-timestamp}.{corpo bruto}"}, com
 * HMAC-SHA256 usando a parte base64 do segredo {@code whsec_...}. O header
 * {@code svix-signature} traz uma ou mais assinaturas {@code v1,<base64>} separadas
 * por espaço. Mensagens com timestamp a mais de 5 minutos do relógio local são
 * recusadas para impedir replay.
 *
 * <p>O corpo precisa ser exatamente o recebido na requisição: re-serializar o JSON
 * invalida a assinatura. Use o {@code svix-id} como chave de deduplicação, pois ele
 * se repete nas retentativas.
 */
@Component
public class BlindPayWebhookVerifier {

    public static final String MESSAGE_ID_HEADER = "svix-id";
    public static final String TIMESTAMP_HEADER = "svix-timestamp";
    public static final String SIGNATURE_HEADER = "svix-signature";

    static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private static final String SECRET_PREFIX = "whsec_";
    private static final String SIGNATURE_VERSION_PREFIX = "v1,";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BlindPayProperties properties;
    private final Clock clock;

    @Autowired
    public BlindPayWebhookVerifier(BlindPayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    BlindPayWebhookVerifier(BlindPayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return {@code true} somente se alguma assinatura confere e o timestamp está
     * dentro da janela de tolerância
     * @throws BlindPayIntegrationException se o segredo do webhook não estiver configurado
     */
    public boolean isValid(String messageId, String timestamp, String signatureHeader, byte[] rawBody) {
        byte[] key = signingKey();
        if (isBlank(messageId) || isBlank(timestamp) || isBlank(signatureHeader) || rawBody == null) {
            return false;
        }
        if (!isWithinTolerance(timestamp)) {
            return false;
        }

        byte[] expected = sign(key, messageId, timestamp, rawBody)
                .getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatureHeader.trim().split(" +")) {
            if (candidate.startsWith(SIGNATURE_VERSION_PREFIX)) {
                byte[] signature = candidate.substring(SIGNATURE_VERSION_PREFIX.length())
                        .getBytes(StandardCharsets.US_ASCII);
                if (MessageDigest.isEqual(expected, signature)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWithinTolerance(String timestamp) {
        long signedAtSeconds;
        try {
            signedAtSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        long nowSeconds = clock.instant().getEpochSecond();
        return Math.abs(nowSeconds - signedAtSeconds) <= TIMESTAMP_TOLERANCE.toSeconds();
    }

    private String sign(byte[] key, String messageId, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (GeneralSecurityException ex) {
            throw BlindPayIntegrationException.configuration("Could not compute the BlindPay webhook signature", ex);
        }
    }

    private byte[] signingKey() {
        String secret = properties.getWebhookSecret();
        if (isBlank(secret)) {
            throw BlindPayIntegrationException.configuration("BLINDPAY_WEBHOOK_SECRET is not configured", null);
        }
        String encodedKey = secret.trim().startsWith(SECRET_PREFIX)
                ? secret.trim().substring(SECRET_PREFIX.length())
                : secret.trim();
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException ex) {
            key = new byte[0];
        }
        if (key.length == 0) {
            throw BlindPayIntegrationException.configuration("BLINDPAY_WEBHOOK_SECRET is not a valid whsec_ secret", null);
        }
        return key;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
