package ages.vstable.backend.utils;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationTokenCipherTest {

    private static final String KEY = "dGVzdC1vbmx5LWludml0ZS1rZXktMzItYnl0ZXMhISE=";
    private static final String OTHER_KEY = "b3V0cmEtY2hhdmUtZGUtdGVzdGUtMzItYnl0ZXMhISE=";
    private static final String TOKEN = "kX9fQ-opaque_token-0123456789abcdefghijklmnopqrs";

    private final InvitationTokenCipher cipher = new InvitationTokenCipher(KEY, "v1");

    @Test
    void decrypt_ofWhatWasEncrypted_returnsTheSameToken() {
        // Act
        String ciphertext = cipher.encrypt(TOKEN);

        // Assert
        assertThat(cipher.decrypt(ciphertext, "v1")).isEqualTo(TOKEN);
    }

    @Test
    void encrypt_neverStoresThePlainToken() {
        // Act
        String ciphertext = cipher.encrypt(TOKEN);

        // Assert
        assertThat(ciphertext).doesNotContain(TOKEN);
        assertThat(new String(Base64.getDecoder().decode(ciphertext), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(TOKEN);
    }

    @Test
    void encrypt_sameTokenTwice_producesDifferentCiphertexts() {
        // Act / Assert: o IV é aleatório a cada cifra.
        assertThat(cipher.encrypt(TOKEN)).isNotEqualTo(cipher.encrypt(TOKEN));
    }

    @Test
    void keyVersion_isTheConfiguredOne() {
        assertThat(cipher.keyVersion()).isEqualTo("v1");
    }

    @Test
    void decrypt_ofACopyMadeWithAnotherKeyVersion_isRejected() {
        // Arrange
        String ciphertext = cipher.encrypt(TOKEN);

        // Act / Assert
        assertThatThrownBy(() -> cipher.decrypt(ciphertext, "v0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Versão de chave");
    }

    @Test
    void decrypt_withAnotherKey_fails() {
        // Arrange
        String ciphertext = cipher.encrypt(TOKEN);
        InvitationTokenCipher otherCipher = new InvitationTokenCipher(OTHER_KEY, "v1");

        // Act / Assert
        assertThatThrownBy(() -> otherCipher.decrypt(ciphertext, "v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_ofATamperedCiphertext_fails() {
        // Arrange
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt(TOKEN));
        bytes[bytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(bytes);

        // Act / Assert
        assertThatThrownBy(() -> cipher.decrypt(tampered, "v1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_ofAPayloadShorterThanOrEqualToTheIv_failsWithInvalidPayloadCause() {
        // Act / Assert
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[8]), "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[12]), "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Payload criptografado inválido");
    }

    @Test
    void constructor_withAKeyThatIsNot32Bytes_isRejected() {
        // Arrange
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        // Act / Assert
        assertThatThrownBy(() -> new InvitationTokenCipher(shortKey, "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
