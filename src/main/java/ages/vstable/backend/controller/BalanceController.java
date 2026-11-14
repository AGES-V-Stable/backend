package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.dto.balance.AddBalanceRequest;
import ages.vstable.backend.dto.balance.MockTopUpRequest;
import ages.vstable.backend.dto.balance.MockTopUpResponse;
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

    /**
     * Contract requested by backend #38. This is an alias for the existing simulated-credit
     * operation, not a second credit mechanism: environment, owner, KYC and row-lock rules remain.
     */
    @PostMapping("/mock-top-ups")
    @Operation(summary = "Credita saldo fictício na própria empresa (ambientes habilitados)",
            description = "Aceita amountBrl; não movimenta dinheiro real. Requer OWNER e KYC aprovado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Crédito e saldo atualizado"),
            @ApiResponse(responseCode = "400", description = "Valor inválido ou com mais de duas casas"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Sem autorização de proprietário ou KYC"),
            @ApiResponse(responseCode = "404", description = "Funcionalidade desabilitada ou empresa ausente"),
            @ApiResponse(responseCode = "409", description = "O crédito ultrapassaria numeric(15,2)"),
    })
    public ResponseEntity<MockTopUpResponse> addMockTopUp(
            @Valid @RequestBody MockTopUpRequest request,
            Authentication authentication) {
        UserEntity user = CompanyAccess.companyUser(authentication).orElse(null);
        BalanceResponse updated = balanceService.addBalance(user, request.amountBrl());
        return ResponseEntity.ok(new MockTopUpResponse(
                request.amountBrl().setScale(2).toPlainString(), updated.availableBalanceBrl()));
    }

    @PostMapping("/simulated-deposits")
    @Operation(summary = "Credita saldo simulado em BRL na empresa do proprietário autenticado",
            description = "Só existe nos ambientes com balance.simulated-deposits.enabled=true; não movimenta dinheiro real.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saldo atualizado da empresa"),
            @ApiResponse(responseCode = "400", description = "Valor ausente, não positivo ou com mais de duas casas decimais"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Não é o proprietário (OWNER_REQUIRED, COMPANY_ACCESS_DENIED, COMPANY_ACCESS_REVOKED) ou KYC não aprovado (KYC_NOT_APPROVED)"),
            @ApiResponse(responseCode = "404", description = "Crédito simulado desabilitado no ambiente (SIMULATED_DEPOSITS_DISABLED) ou empresa não encontrada"),
            @ApiResponse(responseCode = "409", description = "O novo saldo passaria do limite (BALANCE_LIMIT_EXCEEDED)"),
    })
    public ResponseEntity<BalanceResponse> addSimulatedBalance(
            @Valid @RequestBody AddBalanceRequest request,
            Authentication authentication) {
        UserEntity user = CompanyAccess.companyUser(authentication).orElse(null);
        return ResponseEntity.ok(balanceService.addBalance(user, request.amount()));
    }
}
