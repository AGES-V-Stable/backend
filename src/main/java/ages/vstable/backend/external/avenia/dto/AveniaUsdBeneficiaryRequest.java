package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AveniaUsdBeneficiaryRequest(
        String alias,
        String description,
        String bankAccountNumber,
        String bankRoutingNumber,
        String bankBeneficiaryName,
        String bankName,
        Address beneficiaryAddress,
        @JsonIgnore String subAccountId
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(
            String streetLine1,
            String streetLine2,
            String city,
            String state,
            String postalCode,
            String country
    ) {
    }
}
