package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AveniaAppliedFee(
        String type,
        String description,
        BigDecimal amount,
        String currency
) {
}
