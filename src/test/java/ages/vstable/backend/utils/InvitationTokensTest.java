package ages.vstable.backend.utils;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationTokensTest {

    private final InvitationTokens tokens = new InvitationTokens();

    @Test
    void generate_returnsAnOpaqueBase64UrlTokenOf256Bits() {
        // Act
        String token = tokens.generate();

        // Assert
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void generate_neverRepeatsAToken() {
        // Act
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            generated.add(tokens.generate());
        }

        // Assert
        assertThat(generated).hasSize(2000);
    }

    @Test
    void hash_isTheLowercaseHexSha256OfTheToken() {
        // Act / Assert: vetor de teste conhecido do SHA-256
        assertThat(tokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hash_fitsTheColumnAndNeverContainsTheToken() {
        // Arrange
        String token = tokens.generate();

        // Act
        String hash = tokens.hash(token);

        // Assert
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(token);
        assertThat(tokens.hash(token)).isEqualTo(hash);
        assertThat(tokens.hash(tokens.generate())).isNotEqualTo(hash);
    }
}
