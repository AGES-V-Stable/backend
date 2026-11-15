package ages.vstable.backend.dto.blindpay;

/** Only provider identifiers and verification status; never returns PII or document URLs. */
public record BlindPayIndividualKycStatus(
        boolean termsAccepted,
        boolean submitted,
        String kycStatus,
        String amlStatus
) {
}
