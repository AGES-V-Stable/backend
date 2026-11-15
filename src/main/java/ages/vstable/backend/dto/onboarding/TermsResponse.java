package ages.vstable.backend.dto.onboarding;

/** Termos de uso e política de privacidade vigentes, exigidos na criação de conta. */
public record TermsResponse(String version, String termsUrl, String privacyUrl) {
}
