package ages.vstable.backend.dto.exchangerate;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Cotações de referência dos pares configurados")
public record ExchangeRatesResponse(
        List<ExchangeRateResponse> rates,
        @Schema(description = "Idade máxima de uma cotação CURRENT, em segundos", example = "7200") long ttlSeconds) {
}
