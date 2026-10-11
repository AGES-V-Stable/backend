package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/payin-quotes. O destino das stablecoins
 * é uma carteira externa cadastrada ({@code blockchainWalletId}) ou uma carteira
 * gerenciada pela BlindPay ({@code walletId}), nunca ambas.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayPayinQuoteRequest(
        String blockchainWalletId,
        String walletId,
        BlindPayApi.Payin.PaymentMethod paymentMethod,
        BlindPayApi.VirtualAccount.Token token,
        BlindPayApi.Quote.CurrencyType currencyType,
        Long requestAmount,
        Boolean coverFees,
        String partnerFeeId
) {

    public BlindPayPayinQuoteRequest {
        RequestValidation.requireExactlyOne(
                blockchainWalletId, "blockchainWalletId", walletId, "walletId");
        RequestValidation.requireNonNull(paymentMethod, "paymentMethod");
        RequestValidation.requireNonNull(token, "token");
        RequestValidation.requireNonNull(currencyType, "currencyType");
        RequestValidation.requireMinimumAmount(
                requestAmount, BlindPayApi.Quote.REQUEST_AMOUNT_MIN_CENTS, "requestAmount");
    }
}
