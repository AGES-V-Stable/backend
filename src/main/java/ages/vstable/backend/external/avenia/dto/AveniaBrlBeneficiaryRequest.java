package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AveniaBrlBeneficiaryRequest(
        String alias,
        String description,
        String pixKey,
        String taxId,
        String userName,
        String bankCode,
        String branchCode,
        String accountNumber,
        String accountType,
        @JsonIgnore String subAccountId
) {
}
