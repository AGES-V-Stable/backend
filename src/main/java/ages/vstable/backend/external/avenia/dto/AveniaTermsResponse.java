package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * GET /v2/terms/{user-id}: termos de uso e política de privacidade vigentes da Avenia
 * para a conta (na subconta, {@code user-id} é o próprio id da subconta). O
 * {@code acceptanceToken} vale 2 horas e nunca sai do backend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AveniaTermsResponse(
        String userId,
        Bundle bundle,
        List<Document> documents,
        Integer currentVersion,
        Integer acceptedVersion,
        String acceptedAt,
        String url,
        Boolean required,
        String acceptanceToken
) {

    public AveniaTermsResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /** A versão vigente já foi aceita pela conta. */
    public boolean accepted() {
        return currentVersion != null && Objects.equals(currentVersion, acceptedVersion);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bundle(String key, Integer version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(Integer order, String documentKey, String displayName, String contentUrl) {
    }
}
