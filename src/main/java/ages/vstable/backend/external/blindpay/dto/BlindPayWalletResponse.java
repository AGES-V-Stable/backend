package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayWalletResponse(
        String id,
        String name,
        String externalId,
        String address,
        BlindPayApi.BlockchainWallet.Network network,
        OffsetDateTime createdAt
) {
}
