package ages.vstable.backend.external.avenia.dto;

import java.util.Map;

/**
 * POST /v2/terms/{user-id}/accept. {@code clientMetadata} guarda a evidência do
 * consentimento coletado pela V-Stable (até 64 KiB, sem dados pessoais).
 */
public record AveniaTermsAcceptRequest(
        Integer bundleVersion,
        String acceptanceToken,
        String channel,
        Map<String, String> clientMetadata
) {
}
