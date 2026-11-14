package ages.vstable.backend.utils;

import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.exception.PreconditionRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IfMatchHeaderTest {

    @Test
    void requireVersion_quotedDecimal_returnsTheVersion() {
        assertThat(IfMatchHeader.requireVersion("\"3\"")).isEqualTo(3);
        assertThat(IfMatchHeader.requireVersion("  \"12\"  ")).isEqualTo(12);
        assertThat(IfMatchHeader.requireVersion("\"1\"")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void requireVersion_missingOrBlank_isPreconditionRequired(String header) {
        assertThatThrownBy(() -> IfMatchHeader.requireVersion(header))
                .isInstanceOfSatisfying(PreconditionRequiredException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VERSION_REQUIRED"));
    }

    @Test
    void requireVersion_absentHeader_isPreconditionRequired() {
        assertThatThrownBy(() -> IfMatchHeader.requireVersion(null))
                .isInstanceOfSatisfying(PreconditionRequiredException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VERSION_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "*", "W/\"3\"", "\"abc\"", "\"-1\"", "\"\"", "\"3.5\"", "\"1234567890123456789\"",
            "\"3\", \"4\""})
    void requireVersion_anythingButAQuotedDecimal_isAnInvalidParameter(String header) {
        assertThatThrownBy(() -> IfMatchHeader.requireVersion(header))
                .isInstanceOf(InvalidParameterException.class);
    }
}
