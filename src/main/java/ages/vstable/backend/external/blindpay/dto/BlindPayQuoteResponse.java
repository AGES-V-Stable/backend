package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/**
 * Cotação de payout. Valores monetários vêm em centavos; {@code expiresAt} é epoch
 * em milissegundos. {@code contract} traz os dados do {@code approve} ERC-20 que a carteira
 * de origem precisa assinar antes de POST /payouts/evm.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayQuoteResponse(
        String id,
        Long expiresAt,
        BigDecimal commercialQuotation,
        BigDecimal blindpayQuotation,
        BigDecimal receiverAmount,
        BigDecimal senderAmount,
        BigDecimal partnerFeeAmount,
        BigDecimal flatFee,
        BigDecimal billingFeeAmount,
        BigDecimal customerLocalAmount,
        String description,
        Contract contract
) {

    /** A BlindPay envia este objeto em camelCase, diferente do restante da API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contract(
            JsonNode abi,
            String address,
            String functionName,
            String blindpayContractAddress,
            String amount,
            Network network
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Network(
            String name,
            Long chainId
    ) {
    }
}
