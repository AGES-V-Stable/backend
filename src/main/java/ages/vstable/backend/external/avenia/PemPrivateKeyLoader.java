package ages.vstable.backend.external.avenia;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class PemPrivateKeyLoader {

    private PemPrivateKeyLoader() {
    }

    static PrivateKey load(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("AVENIA_PRIVATE_KEY is not configured");
        }

        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        try {
            byte[] encoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalArgumentException("AVENIA_PRIVATE_KEY must be a valid PKCS#8 RSA private key", ex);
        }
    }
}
