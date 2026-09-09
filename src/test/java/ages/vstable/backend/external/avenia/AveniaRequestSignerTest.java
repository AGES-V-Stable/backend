package ages.vstable.backend.external.avenia;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

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
}
