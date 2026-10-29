package ages.vstable.backend.dto.balance;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Saldo disponível da empresa autenticada")
public record BalanceResponse(
        @Schema(description = "Saldo disponível em BRL, string decimal com duas casas", example = "485200.00")
        String availableBalanceBrl) {
}
