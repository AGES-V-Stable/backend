package ages.vstable.backend.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Série mensal do dashboard (volume de remessas ou economia)")
public record DashboardSeriesResponse(
        @Schema(example = "BRL") String currency,
        @Schema(example = "MONTH") String granularity,
        LocalDate startDate,
        LocalDate endDate,
        @Schema(description = "Soma dos pontos", example = "338000.00") String total,
        @Schema(description = "Um ponto por mês do período; vazio quando não há nenhuma operação liquidada")
        List<Point> points,
        @Schema(description = "Operações liquidadas fora da soma por falta de dados") long incompleteOperations) {

    public record Point(
            @Schema(description = "Mês civil em America/Sao_Paulo", example = "2026-05") String month,
            @Schema(description = "Zero quando o mês não teve operações", example = "56000.00") String amount) {
    }
}
