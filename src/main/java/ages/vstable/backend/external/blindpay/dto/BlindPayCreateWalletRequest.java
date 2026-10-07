package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayCreateWalletRequest(
        BlindPayApi.BlockchainWallet.Network network,
        String externalId,
        String name
) {
    public BlindPayCreateWalletRequest {
        RequestValidation.requireNonNull(network, "network");
        RequestValidation.requireText(name, "name");
    }
}
