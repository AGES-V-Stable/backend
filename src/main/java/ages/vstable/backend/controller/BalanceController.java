package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.dto.balance.AddBalanceRequest;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.service.BalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/** Saldo da empresa do usuário autenticado; não existe parâmetro para escolher a empresa. */
@RestController
@RequestMapping("/v1/balance")
@RequiredArgsConstructor
@Tag(name = "Balance", description = "Saldo disponível da empresa autenticada")
public class BalanceController {

    private final BalanceService balanceService;

    @GetMapping
    @Operation(summary = "Consulta o saldo disponível em BRL da empresa do usuário autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saldo disponível da empresa"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Sem vínculo ativo com permissão de consulta (COMPANY_ACCESS_DENIED, COMPANY_ACCESS_REVOKED ou PERMISSION_DENIED)"),
            @ApiResponse(responseCode = "404", description = "Empresa não encontrada"),
    })
    public ResponseEntity<BalanceResponse> getBalance(Authentication authentication) {
        UUID companyId = CompanyAccess.requireCompanyId(authentication, CompanyPermission.BALANCE_READ);
        return ResponseEntity.ok(balanceService.getBalance(companyId));
    }

    @PostMapping("/simulated-deposits")
    public ResponseEntity<BalanceResponse> addSimulatedBalance(
            @Valid @RequestBody AddBalanceRequest request,
            Authentication authentication) {
        UserEntity user = CompanyAccess.companyUser(authentication).orElse(null);
        return ResponseEntity.ok(balanceService.addBalance(user, request.amount()));
    }
}
