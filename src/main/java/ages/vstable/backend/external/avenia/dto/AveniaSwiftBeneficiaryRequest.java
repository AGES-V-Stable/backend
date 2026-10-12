package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AveniaSwiftBeneficiaryRequest(
        String alias,
        String description,
        String beneficiaryName,
        String beneficiaryType,
        String accountStandard,
        String accountValue,
        String bic,
        String bankName,
        String bankCountry,
        String beneficiaryStreet,
        String beneficiaryCity,
        String beneficiaryState,
        String beneficiaryPostalCode,
        String beneficiaryCountry,
        String currency,
        String intermediaryBic,
        String bankOtherIdentifier,
        @JsonIgnore String subAccountId
) {
}
