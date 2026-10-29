package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/payouts/evm. {@code senderWalletAddress}
 * é sempre obrigatório: é o endereço da carteira de origem, seja ela externa (que já
 * aprovou o contrato da cotação) ou gerenciada pela BlindPay. Para a carteira
 * gerenciada, {@code walletId} identifica a carteira cujo saldo será debitado.
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
        RequestValidation.requireText(senderWalletAddress, "senderWalletAddress");
        if (walletId != null && walletId.isBlank()) {
            throw new IllegalArgumentException("walletId must not be blank");
        }
    }

    public static BlindPayEvmPayoutRequest fromExternalWallet(String quoteId, String senderWalletAddress) {
        return new BlindPayEvmPayoutRequest(quoteId, senderWalletAddress, null);
    }

    public static BlindPayEvmPayoutRequest fromManagedWallet(String quoteId, String walletAddress, String walletId) {
        RequestValidation.requireText(walletId, "walletId");
        return new BlindPayEvmPayoutRequest(quoteId, walletAddress, walletId);
    }
}
