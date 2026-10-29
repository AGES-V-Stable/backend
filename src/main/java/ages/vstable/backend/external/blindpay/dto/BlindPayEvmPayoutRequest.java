package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/payouts/evm. A origem dos fundos é
 * informada por exatamente um campo: {@code senderWalletAddress} ou {@code walletId}.
 * A API recusa os dois juntos ("Provide exactly one of sender_wallet_address or
 * wallet_id"), conferido na instância de desenvolvimento. Para a carteira gerenciada
 * pela BlindPay, o payout é enviado com o endereço dela.
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

    /** Carteira gerenciada pela BlindPay: a origem é o endereço dela. */
    public static BlindPayEvmPayoutRequest fromManagedWallet(String quoteId, String walletAddress) {
        return new BlindPayEvmPayoutRequest(quoteId, walletAddress, null);
    }
}
