package ages.vstable.backend.external.avenia;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

@Component
public class AveniaRequestSigner {

    public String sign(String timestamp, String method, String uri, String body, PrivateKey privateKey) {
        String stringToSign = timestamp + method + uri + (body == null ? "" : body);

        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao assinar requisição para a Avenia", e);
        }
    }
}
