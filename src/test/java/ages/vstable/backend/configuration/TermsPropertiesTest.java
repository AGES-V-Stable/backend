package ages.vstable.backend.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermsPropertiesTest {

    private static final String TERMS = "https://vstable.example/termos-de-uso";
    private static final String PRIVACY = "https://vstable.example/politica-de-privacidade";

    @Test
    void validConfiguration_isExposedAsIs() {
        // Act
        TermsProperties terms = new TermsProperties("2026-11-07", TERMS, PRIVACY);

        // Assert
        assertThat(terms.version()).isEqualTo("2026-11-07");
        assertThat(terms.termsUrl()).isEqualTo(TERMS);
        assertThat(terms.privacyUrl()).isEqualTo(PRIVACY);
    }

    @Test
    void originWithTrailingSlash_doesNotProduceADoubleSlashInTheLinks() {
        // Arrange: INVITATION_FRONTEND_ORIGIN=http://localhost:5173/ gera "//termos-de-uso" no padrão.
        TermsProperties terms = new TermsProperties("2026-11-07", "http://localhost:5173//termos-de-uso?x=1#y",
                "http://localhost:5173//politica-de-privacidade");

        // Assert
        assertThat(terms.termsUrl()).isEqualTo("http://localhost:5173/termos-de-uso?x=1#y");
        assertThat(terms.privacyUrl()).isEqualTo("http://localhost:5173/politica-de-privacidade");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", " v1", "v1 "})
    void blankOrPaddedVersion_failsAtStartup(String version) {
        // Act / Assert
        assertThatThrownBy(() -> new TermsProperties(version, TERMS, PRIVACY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.terms.version");
    }

    @Test
    void versionLongerThanTheDatabaseColumn_failsAtStartupInsteadOfOnAcceptance() {
        // Act / Assert
        assertThatThrownBy(() -> new TermsProperties("v".repeat(TermsProperties.MAX_VERSION_LENGTH + 1), TERMS, PRIVACY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new TermsProperties("v".repeat(TermsProperties.MAX_VERSION_LENGTH), TERMS, PRIVACY).version())
                .hasSize(TermsProperties.MAX_VERSION_LENGTH);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "/termos", "termos-de-uso", "javascript:alert(1)", "ftp://vstable.example/t",
            "https://", "http:// espaço"})
    void urlThatIsNotAbsoluteHttp_failsAtStartup(String url) {
        // Act / Assert
        assertThatThrownBy(() -> new TermsProperties("v1", url, PRIVACY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.terms.terms-url");
        assertThatThrownBy(() -> new TermsProperties("v1", TERMS, url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.terms.privacy-url");
    }
}
