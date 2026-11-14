package ages.vstable.backend.dto.exchangerate;

import ages.vstable.backend.service.exchangerate.ExchangeRateStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Cotação de referência de um par")
public record ExchangeRateResponse(
        @Schema(description = "Moeda de origem da conversão", example = "BRL") String baseCurrency,
        @Schema(description = "Moeda de destino da conversão", example = "USDC") String quoteCurrency,
        @Schema(description = "Preço de mercado da Avenia (basePrice), sem tarifas: quanto de baseCurrency vale 1 "
                + "quoteCurrency, como string decimal; nulo quando UNAVAILABLE", example = "5.47000000")
        String rate,
        @Schema(description = "Momento em que a cotação foi obtida da Avenia; nulo quando UNAVAILABLE",
                example = "2026-11-12T22:45:00Z")
        OffsetDateTime fetchedAt,
        @Schema(description = "CURRENT: dentro do TTL; STALE: última cotação salva, a atualização falhou; "
                + "UNAVAILABLE: nunca houve cotação")
        ExchangeRateStatus status) {
}
