package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.service.dashboard.DashboardPeriod;
import ages.vstable.backend.service.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Dashboard da empresa do usuário autenticado; não existe parâmetro para escolher a empresa. */
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Métricas da empresa autenticada")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Data em formato inválido ou startDate posterior a endDate"),
        @ApiResponse(responseCode = "401", description = "Não autenticado"),
        @ApiResponse(responseCode = "403", description = "Principal sem vínculo empresarial (ex.: Administrador)"),
})
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Economia acumulada estimada no período")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            Authentication authentication,
            @Parameter(description = "Data inicial (AAAA-MM-DD, fuso America/Sao_Paulo)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data final inclusiva (AAAA-MM-DD, fuso America/Sao_Paulo)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID companyId = companyId(authentication);
        return ResponseEntity.ok(dashboardService.summary(companyId, new DashboardPeriod(startDate, endDate)));
    }

    @GetMapping("/remittance-volume")
    @Operation(summary = "Volume mensal em BRL das remessas liquidadas")
    public ResponseEntity<DashboardSeriesResponse> getRemittanceVolume(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID companyId = companyId(authentication);
        return ResponseEntity.ok(dashboardService.remittanceVolume(companyId, new DashboardPeriod(startDate, endDate)));
    }

    @GetMapping("/savings-history")
    @Operation(summary = "Economia mensal estimada das operações liquidadas")
    public ResponseEntity<DashboardSeriesResponse> getSavingsHistory(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID companyId = companyId(authentication);
        return ResponseEntity.ok(dashboardService.savingsHistory(companyId, new DashboardPeriod(startDate, endDate)));
    }

    @GetMapping("/settlements")
    @Operation(summary = "Transferências mais recentes, de qualquer status, paginadas")
    public ResponseEntity<Page<DashboardSettlementResponse>> getSettlements(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Página, base 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por página, de 1 a 50") @RequestParam(defaultValue = "5") int size) {
        UUID companyId = companyId(authentication);
        return ResponseEntity.ok(dashboardService.settlements(companyId, new DashboardPeriod(startDate, endDate), page, size));
    }

    private static UUID companyId(Authentication authentication) {
        return CompanyAccess.companyUser(authentication)
                .map(UserEntity::getCompanyId)
                .orElseThrow(() -> new ForbiddenException("Company access is required"));
    }
}
