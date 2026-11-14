package ages.vstable.backend.dto.balance;

import io.swagger.v3.oas.annotations.media.Schema;

/** Nenhuma movimentação real é feita: valores em BRL como strings com duas casas. */
public record MockTopUpResponse(
        @Schema(example = "150.75") String creditedAmountBrl,
        @Schema(example = "263.50") String availableBalanceBrl
) {
}
