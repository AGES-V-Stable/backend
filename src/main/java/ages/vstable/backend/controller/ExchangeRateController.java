package ages.vstable.backend.controller;

import ages.vstable.backend.dto.exchangerate.ExchangeRatesResponse;
import ages.vstable.backend.service.exchangerate.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cotações globais de referência, para cliente PME e administrador (não dependem da empresa). */
@RestController
@RequestMapping("/v1/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Câmbio", description = "Cotações de referência das moedas disponíveis")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    @Operation(summary = "Cotação atual de cada par configurado",
            description = "Preço de mercado da Avenia, sem tarifas. Uma cotação com mais que o TTL é buscada de "
                    + "novo; se a Avenia falhar, volta a última salva como STALE, ou UNAVAILABLE se nunca houve.")
    @ApiResponse(responseCode = "200", description = "Cotações, uma por par, mesmo quando indisponíveis")
    @ApiResponse(responseCode = "401", description = "Não autenticado")
    public ResponseEntity<ExchangeRatesResponse> getExchangeRates() {
        return ResponseEntity.ok(exchangeRateService.currentRates());
    }
}
