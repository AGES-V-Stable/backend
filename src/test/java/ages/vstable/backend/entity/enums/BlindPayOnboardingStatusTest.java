package ages.vstable.backend.entity.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BlindPayOnboardingStatusTest {

    @ParameterizedTest
    @CsvSource({
            "approved,     clear, true,  APPROVED",
            "approved_rfi, clear, true,  APPROVED",
            "approved,     ,      true,  AML_PENDING",
            "approved,     error, true,  AML_PENDING",
            "approved,     hit,   true,  AML_HIT",
            "approved,     clear, false, TOS_PENDING",
            "verifying,    clear, true,  VERIFYING",
            "pending_review, clear, true, PENDING_REVIEW",
            "awaiting_contract, clear, true, AWAITING_CONTRACT",
            "compliance_request, clear, true, RFI_REQUESTED",
            "rejected,     clear, true,  REJECTED",
            "deprecated,   clear, true,  DEPRECATED",
            "APPROVED,     CLEAR, true,  APPROVED",
            "suspended,    clear, true,  VERIFYING",
            ",             clear, true,  VERIFYING",
    })
    void fromProvider_mapsKycAmlAndTermsToOnboardingStep(
            String kycStatus, String amlStatus, boolean tosAccepted, BlindPayOnboardingStatus expected) {
        // Act
        BlindPayOnboardingStatus status = BlindPayOnboardingStatus.fromProvider(kycStatus, amlStatus, tosAccepted);

        // Assert
        assertThat(status).isEqualTo(expected);
    }

    @Test
    void parse_legacyOrMissingValue_returnsNull() {
        assertThat(BlindPayOnboardingStatus.parse("CREATED")).isNull();
        assertThat(BlindPayOnboardingStatus.parse(null)).isNull();
        assertThat(BlindPayOnboardingStatus.parse("APPROVED")).isEqualTo(BlindPayOnboardingStatus.APPROVED);
    }

    @Test
    void isTerminalRejection_onlyForRejectedAndDeprecated() {
        assertThat(BlindPayOnboardingStatus.REJECTED.isTerminalRejection()).isTrue();
        assertThat(BlindPayOnboardingStatus.DEPRECATED.isTerminalRejection()).isTrue();
        assertThat(BlindPayOnboardingStatus.RFI_REQUESTED.isTerminalRejection()).isFalse();
        assertThat(BlindPayOnboardingStatus.AML_HIT.isTerminalRejection()).isFalse();
    }
}
