package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.service.TransactionVolumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Volume transacionado da empresa do usuário autenticado; não existe parâmetro para escolher a empresa. */
@RestController
@RequestMapping("/v1/dashboard/transaction-volume")
@RequiredArgsConstructor
@Tag(name = "Transaction volume", description = "Volume transacionado da empresa autenticada")
public class TransactionVolumeController {

    private final TransactionVolumeService transactionVolumeService;

    @GetMapping
    @Operation(summary = "Soma em BRL das transações liquidadas da empresa do usuário autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Volume transacionado; zero quando não há transações"),
            @ApiResponse(responseCode = "400", description = "startDate em formato inválido"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Principal sem vínculo empresarial (ex.: Administrador)"),
    })
    public ResponseEntity<TransactionVolumeResponse> getTransactionVolume(
            Authentication authentication,
            @Parameter(description = "Data inicial (AAAA-MM-DD, fuso America/Sao_Paulo); sem ela, considera todo o histórico")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {
        UserEntity user = CompanyAccess.companyUser(authentication)
                .filter(companyUser -> companyUser.getCompanyId() != null)
                .orElseThrow(() -> new ForbiddenException("Company access is required"));
        return ResponseEntity.ok(transactionVolumeService.getSettledVolume(user.getCompanyId(), startDate));
    }
}
