package ages.vstable.backend.external.avenia;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemPrivateKeyLoaderTest {

    @Test
    void load_pemValido_retornaChavePrivadaEquivalente() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        PrivateKey loaded = PemPrivateKeyLoader.load(pem);

        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    // Formato usado em .env / docker-compose: newlines reais viram "\n" literal (2 caracteres)
    @Test
    void load_pemComQuebraDeLinhaLiteralEscapada_retornaChavePrivadaEquivalente() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";

        PrivateKey loaded = PemPrivateKeyLoader.load(pem);

        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    void load_pemVazio_lancaIllegalStateException() {
        assertThatThrownBy(() -> PemPrivateKeyLoader.load(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void load_pemNulo_lancaIllegalStateException() {
        assertThatThrownBy(() -> PemPrivateKeyLoader.load(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void load_pemInvalido_lancaIllegalStateException() {
        assertThatThrownBy(() -> PemPrivateKeyLoader.load(
                "-----BEGIN PRIVATE KEY-----\nnao-e-base64-valido!!!\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }
}
