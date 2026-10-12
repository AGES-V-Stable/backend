package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AveniaWalletBeneficiaryRequest(
        String alias,
        String description,
        String walletAddress,
        String walletChain,
        String walletMemo,
        @JsonIgnore String subAccountId
) {
}
