package ages.vstable.backend.external.avenia;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AveniaRequestSignerTest {

    private final AveniaRequestSigner signer = new AveniaRequestSigner();

    private KeyPair gerarKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    // Dado timestamp, metodo, uri e chave fixos, a assinatura deve ser validavel com a chave publica correspondente
    @Test
    void sign_geraAssinaturaValidaVerificavelComAChavePublica() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        String timestamp = "1743107780606";
        String method = "GET";
        String uri = "/v2/account/account-info";

        String signatureBase64 = signer.sign(timestamp, method, uri, null, keyPair.getPrivate());

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((timestamp + method + uri).getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(Base64.getDecoder().decode(signatureBase64))).isTrue();
    }

    @Test
    void sign_incluiCorpoNaStringAssinadaQuandoPresente() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        String timestamp = "1743107780606";
        String method = "POST";
        String uri = "/v2/documents/";
        String body = "{\"documentType\":\"SELFIE-FROM-LIVENESS\"}";

        String signatureBase64 = signer.sign(timestamp, method, uri, body, keyPair.getPrivate());

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((timestamp + method + uri + body).getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(Base64.getDecoder().decode(signatureBase64))).isTrue();
    }

    @Test
    void sign_ehDeterministica_mesmaEntradaGeraMesmaAssinatura() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        String assinatura1 = signer.sign("123", "POST", "/v2/documents/", "{\"a\":1}", keyPair.getPrivate());
        String assinatura2 = signer.sign("123", "POST", "/v2/documents/", "{\"a\":1}", keyPair.getPrivate());

        assertThat(assinatura1).isEqualTo(assinatura2);
    }

    @Test
    void sign_corpoDiferente_geraAssinaturaDiferente() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        String assinatura1 = signer.sign("123", "POST", "/v2/documents/", "{\"a\":1}", keyPair.getPrivate());
        String assinatura2 = signer.sign("123", "POST", "/v2/documents/", "{\"a\":2}", keyPair.getPrivate());

        assertThat(assinatura1).isNotEqualTo(assinatura2);
    }

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
