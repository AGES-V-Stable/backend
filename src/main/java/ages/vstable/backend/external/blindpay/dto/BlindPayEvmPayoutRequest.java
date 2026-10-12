package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/payouts/evm. A origem dos fundos é
 * uma carteira externa ({@code senderWalletAddress}, que já aprovou o contrato da
 * cotação) ou uma carteira gerenciada pela BlindPay ({@code walletId}), nunca ambas.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayEvmPayoutRequest(
        String quoteId,
        String senderWalletAddress,
        String walletId
) {

    public BlindPayEvmPayoutRequest {
        RequestValidation.requireText(quoteId, "quoteId");
        RequestValidation.requireExactlyOne(
                senderWalletAddress, "senderWalletAddress", walletId, "walletId");
    }

    public static BlindPayEvmPayoutRequest fromExternalWallet(String quoteId, String senderWalletAddress) {
        return new BlindPayEvmPayoutRequest(quoteId, senderWalletAddress, null);
    }

    public static BlindPayEvmPayoutRequest fromManagedWallet(String quoteId, String walletId) {
        return new BlindPayEvmPayoutRequest(quoteId, null, walletId);
    }
}
