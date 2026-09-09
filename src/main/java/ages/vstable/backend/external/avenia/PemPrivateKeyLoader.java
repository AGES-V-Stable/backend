package ages.vstable.backend.external.avenia;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class PemPrivateKeyLoader {

    private PemPrivateKeyLoader() {
    }

    public static PrivateKey load(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("Chave privada da Avenia não configurada (app.avenia.private-key)");
        }

        String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");

        try {
            byte[] decoded = Base64.getDecoder().decode(sanitized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Chave privada da Avenia inválida", e);
        }
    }
}
