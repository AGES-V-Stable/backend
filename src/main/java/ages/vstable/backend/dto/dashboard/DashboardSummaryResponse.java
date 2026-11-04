package ages.vstable.backend.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Economia acumulada estimada no período")
public record DashboardSummaryResponse(
        @Schema(example = "BRL") String currency,
        @Schema(description = "Data inicial aplicada; nula quando o período não tem início") LocalDate startDate,
        @Schema(description = "Data final aplicada (inclusiva); nula quando o período não tem fim") LocalDate endDate,
        @Schema(description = "Economia estimada das operações liquidadas com dados completos; zero quando não há", example = "31450.00")
        String amount,
        @Schema(description = "Custo de câmbio bancário de referência, em pontos percentuais", example = "4.00")
        String referenceSpreadPercentage,
        @Schema(description = "Média do spread efetivo registrado, em pontos percentuais; nula sem registros", example = "0.45")
        String averageEffectiveSpreadPercentage,
        @Schema(description = "Há operações liquidadas sem valor em BRL ou tarifa, fora da soma") boolean incomplete,
        @Schema(description = "Quantidade de operações liquidadas fora da soma por falta de dados") long incompleteOperations) {
}
