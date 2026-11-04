package ages.vstable.backend.controller;

import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.service.TransactionVolumeService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Dashboard administrativo: sem {@code companyId}, consolida todas as empresas. */
@RestController
@RequestMapping("/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin dashboard", description = "Métricas consolidadas ou de uma empresa (Apenas Administradores)")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Parâmetro em formato inválido ou startDate posterior a endDate"),
        @ApiResponse(responseCode = "401", description = "Não autenticado"),
        @ApiResponse(responseCode = "403", description = "Requer perfil ADMIN"),
        @ApiResponse(responseCode = "404", description = "Empresa não encontrada"),
})
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final TransactionVolumeService transactionVolumeService;

    @GetMapping("/summary")
    @Operation(summary = "Economia acumulada estimada no período")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            @Parameter(description = "Empresa; sem ela, todas") @RequestParam(required = false) UUID companyId,
            @Parameter(description = "Data inicial (AAAA-MM-DD, fuso America/Sao_Paulo)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data final inclusiva (AAAA-MM-DD, fuso America/Sao_Paulo)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        DashboardPeriod period = new DashboardPeriod(startDate, endDate);
        dashboardService.requireCompany(companyId);
        return ResponseEntity.ok(dashboardService.summary(companyId, period));
    }

    @GetMapping("/remittance-volume")
    @Operation(summary = "Volume mensal em BRL das remessas liquidadas")
    public ResponseEntity<DashboardSeriesResponse> getRemittanceVolume(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        DashboardPeriod period = new DashboardPeriod(startDate, endDate);
        dashboardService.requireCompany(companyId);
        return ResponseEntity.ok(dashboardService.remittanceVolume(companyId, period));
    }

    @GetMapping("/savings-history")
    @Operation(summary = "Economia mensal estimada das operações liquidadas")
    public ResponseEntity<DashboardSeriesResponse> getSavingsHistory(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        DashboardPeriod period = new DashboardPeriod(startDate, endDate);
        dashboardService.requireCompany(companyId);
        return ResponseEntity.ok(dashboardService.savingsHistory(companyId, period));
    }

    @GetMapping("/settlements")
    @Operation(summary = "Transferências mais recentes, de qualquer status, paginadas")
    public ResponseEntity<Page<DashboardSettlementResponse>> getSettlements(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Página, base 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por página, de 1 a 50") @RequestParam(defaultValue = "5") int size) {
        DashboardPeriod period = new DashboardPeriod(startDate, endDate);
        dashboardService.requireCompany(companyId);
        return ResponseEntity.ok(dashboardService.settlements(companyId, period, page, size));
    }

    @GetMapping("/transaction-volume")
    @Operation(summary = "Soma em BRL das transações liquidadas, criadas no período")
    public ResponseEntity<TransactionVolumeResponse> getTransactionVolume(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        dashboardService.requireCompany(companyId);
        return ResponseEntity.ok(transactionVolumeService.getSettledVolume(companyId, startDate, endDate));
    }
}
