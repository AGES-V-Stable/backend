package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/quotes (cotação de payout para uma
 * conta bancária). {@code requestAmount} é sempre inteiro em centavos: 1000 = 10,00.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayQuoteRequest(
        String bankAccountId,
        BlindPayApi.BlockchainWallet.Network network,
        BlindPayApi.VirtualAccount.Token token,
        BlindPayApi.Quote.CurrencyType currencyType,
        Long requestAmount,
        Boolean coverFees,
        String description,
        String partnerFeeId,
        String refundWalletAddress
) {

    private static final int DESCRIPTION_MAX_LENGTH = 128;

    public BlindPayQuoteRequest {
        RequestValidation.requireText(bankAccountId, "bankAccountId");
        RequestValidation.requireNonNull(network, "network");
        RequestValidation.requireNonNull(token, "token");
        RequestValidation.requireNonNull(currencyType, "currencyType");
        RequestValidation.requireMinimumAmount(
                requestAmount, BlindPayApi.Quote.REQUEST_AMOUNT_MIN_CENTS, "requestAmount");
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("description must have at most 128 characters");
        }
    }
}
