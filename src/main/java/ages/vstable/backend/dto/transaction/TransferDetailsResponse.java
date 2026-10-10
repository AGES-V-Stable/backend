package ages.vstable.backend.dto.transaction;

import ages.vstable.backend.entity.enums.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Detalhes de uma transferência da empresa do usuário autenticado
 * ({@code GET /api/transferencias/{id}}).
 *
 * <p>Valores monetários, cotação e percentuais são strings decimais, sem formatação local.
 * Informação sem registro é {@code null}; nunca é substituída por zero ou por valor de mercado atual.
 */
@Schema(description = "Detalhes de uma transferência, com valores registrados na operação")
public record TransferDetailsResponse(
        UUID id,
        UUID companyId,
        @Schema(description = "Data da operação (ISO 8601, com fuso)") OffsetDateTime date,
        @Schema(description = "Nulo quando a transação não é nem pagamento nem recebimento") Type type,
        @Schema(description = "Código oficial do status da transação") TransactionStatus status,
        String counterpartyName,
        String counterpartyDetails,
        @Schema(description = "Lado que sai: BRL no pagamento; moeda estrangeira no recebimento") Money source,
        @Schema(description = "Lado que entra: moeda estrangeira no pagamento; BRL no recebimento") Money destination,
        @Schema(description = "Método de transferência registrado: ACCOUNT_BALANCE, PIX, TED ou BLOCKCHAIN; nulo em recebimentos") String fundingSource,
        ExchangeRate exchangeRate,
        Costs costs,
        @Schema(description = "Sem referência histórica válida, é nulo") Money estimatedSavings,
        @Schema(description = "Disponibilidade atual do comprovante; o download revalida") boolean receiptAvailable) {

    public enum Type {
        PAGAMENTO,
        RECEBIMENTO
    }

    /** {@code amount} é nulo quando o valor em BRL ainda não foi registrado. */
    public record Money(String amount, String currency) {
    }

    /** Cotação aplicada: 1 {@code fromCurrency} = {@code rate} {@code toCurrency}. */
    public record ExchangeRate(String fromCurrency, String toCurrency, String rate) {
    }

    /** {@code percentage} em pontos percentuais: "0.45" significa 0,45%. */
    public record Cost(String amount, String currency, String percentage) {
    }

    public record Costs(Cost serviceFee, String spreadPercentage, Cost estimatedMarketCost) {
    }
}
