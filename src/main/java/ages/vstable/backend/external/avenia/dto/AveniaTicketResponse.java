package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AveniaTicketResponse(
        UUID id,
        String brCode,
        OffsetDateTime expiration,
        String status,
        String reason,
        String failureReason
) {
}
