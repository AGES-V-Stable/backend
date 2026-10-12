package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AveniaEurBeneficiaryRequest(
        String alias,
        String description,
        String iban,
        String bic,
        String country,
        String bankBeneficiaryName,
        @JsonIgnore String subAccountId
) {
}
