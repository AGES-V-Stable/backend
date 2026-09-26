package ages.vstable.backend.external.avenia;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AveniaRequestSignerTest {

    @Test
    void signsTimestampMethodUriAndBody() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String timestamp = "1770000000000";
        String method = "POST";
        String uri = "/v2/account/tickets/?subAccountId=sub-1";
        String body = "{\"quoteToken\":\"quote-1\"}";

        String encodedSignature = new AveniaRequestSigner()
                .sign(timestamp, method, uri, body, keyPair.getPrivate());

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((timestamp + method + uri + body).getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(Base64.getDecoder().decode(encodedSignature)));
    }

    @Test
    void rejectsPrivateKeyThatCannotSignWithRsa() {
        PrivateKey invalidKey = new PrivateKey() {
            @Override
            public String getAlgorithm() {
                return "INVALID";
            }

            @Override
            public String getFormat() {
                return "RAW";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[]{1};
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new AveniaRequestSigner().sign("1", "GET", "/resource", "", invalidKey));
    }

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
