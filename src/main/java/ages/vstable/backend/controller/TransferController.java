package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Histórico de transferências")
public class TransferController {

    private final TransferService transferService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lista o histórico de transferências de forma paginada, com filtros (Apenas Administradores)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de transferências"),
            @ApiResponse(responseCode = "400", description = "Filtro inválido (datas, valores ou enum)"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Acesso negado (requer perfil ADMIN)"),
    })
    public ResponseEntity<Page<TransferResponse>> findAll(
            @Parameter(description = "Empresa dona da transferência") @RequestParam(required = false) UUID companyId,
            @Parameter(description = "Nome ou CNPJ da empresa") @RequestParam(required = false) String search,
            @Parameter(description = "Nome do beneficiário ou do pagador externo") @RequestParam(required = false) String beneficiary,
            @Parameter(description = "Data inicial (yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data final (yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Valor mínimo (moeda estrangeira)") @RequestParam(required = false) BigDecimal minAmount,
            @Parameter(description = "Valor máximo (moeda estrangeira)") @RequestParam(required = false) BigDecimal maxAmount,
            @Parameter(description = "Status da transação") @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "PAYMENT (pagamento) ou RECEIPT (recebimento)") @RequestParam(required = false) TransferDirection direction,
            @Parameter(description = "Paginação (page base 0, size, sort)")
            @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        TransferFilter filter = new TransferFilter(
                companyId, search, beneficiary, startDate, endDate, minAmount, maxAmount, status, direction);

        return ResponseEntity.ok(transferService.findTransfers(filter, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Consulta os detalhes de uma transferência (Apenas Administradores)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalhes da transferência"),
            @ApiResponse(responseCode = "400", description = "Formato de ID inválido"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Acesso negado (requer perfil ADMIN)"),
            @ApiResponse(responseCode = "404", description = "Transferência não encontrada"),
    })
    public ResponseEntity<TransferResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(transferService.findById(id));
    }
}
