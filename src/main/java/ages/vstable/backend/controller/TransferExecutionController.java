package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.transfer.ConfirmOfferRequest;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.service.transfer.TransferExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Transfers - Execução da melhor oferta")
public class TransferExecutionController {

    private final TransferExecutionService executionService;

    @PostMapping("/v1/quotes/offers/{offerId}/confirm")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Confirma a melhor oferta da cotação agregada com o código TOTP do usuário e executa a transferência pelo provedor vencedor. Repetir a confirmação pelo mesmo usuário devolve a mesma transferência sem consumir outro código.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transferência registrada; retorna o estado atual e, se houver, o código PIX a pagar"),
            @ApiResponse(responseCode = "400", description = "Valores confirmados ou código TOTP ausentes ou com formato inválido"),
            @ApiResponse(responseCode = "401", description = "Código TOTP incorreto, expirado ou já utilizado"),
            @ApiResponse(responseCode = "403", description = "Perfil sem permissão de criar transferências ou vínculo com a empresa removido"),
            @ApiResponse(responseCode = "404", description = "Oferta não encontrada para a empresa do usuário"),
            @ApiResponse(responseCode = "409", description = "O preço mudou no provedor (peça uma nova cotação) ou a oferta já foi confirmada por outro membro"),
            @ApiResponse(responseCode = "422", description = "Oferta expirada, não selecionada, valores diferentes dos da oferta, compliance pendente ou usuário sem 2FA ativo"),
            @ApiResponse(responseCode = "423", description = "Verificação 2FA bloqueada temporariamente por tentativas incorretas"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com o provedor"),
            @ApiResponse(responseCode = "503", description = "Falha temporária no provedor"),
    })
    public ResponseEntity<TransferExecutionResponse> confirm(
            @AuthenticationPrincipal UserEntity currentUser,
            @PathVariable UUID offerId,
            @Valid @RequestBody ConfirmOfferRequest request) {
        CompanyAccess.requireCompanyId(currentUser, CompanyPermission.TRANSFER_CREATE);
        return ResponseEntity.ok(executionService.confirm(currentUser, offerId, request));
    }

    @GetMapping("/v1/transfer-executions/{executionId}")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Consulta o estado de uma transferência confirmada da empresa do usuário")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado atual da transferência"),
            @ApiResponse(responseCode = "404", description = "Transferência não encontrada para a empresa do usuário"),
    })
    public ResponseEntity<TransferExecutionResponse> find(
            @AuthenticationPrincipal UserEntity currentUser,
            @PathVariable UUID executionId) {
        CompanyAccess.requireCompanyId(currentUser, CompanyPermission.TRANSFER_READ);
        return ResponseEntity.ok(executionService.find(currentUser, executionId));
    }

    @PostMapping("/v1/transfer-executions/{executionId}/requote/confirm")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Confirma com o código TOTP do usuário a nova cotação de payout exigida quando o preço mudou durante o financiamento (status REQUOTE_REQUIRED)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nova cotação aceita; retorna o estado atual (pode exigir nova confirmação se ela expirou)"),
            @ApiResponse(responseCode = "400", description = "Valores confirmados ou código TOTP ausentes ou com formato inválido"),
            @ApiResponse(responseCode = "401", description = "Código TOTP incorreto, expirado ou já utilizado"),
            @ApiResponse(responseCode = "403", description = "Perfil sem permissão de criar transferências ou vínculo com a empresa removido"),
            @ApiResponse(responseCode = "404", description = "Transferência não encontrada para a empresa do usuário"),
            @ApiResponse(responseCode = "409", description = "Não há recotação pendente"),
            @ApiResponse(responseCode = "422", description = "Valores diferentes dos da nova cotação ou usuário sem 2FA ativo"),
            @ApiResponse(responseCode = "423", description = "Verificação 2FA bloqueada temporariamente por tentativas incorretas"),
    })
    public ResponseEntity<TransferExecutionResponse> confirmRequote(
            @AuthenticationPrincipal UserEntity currentUser,
            @PathVariable UUID executionId,
            @Valid @RequestBody ConfirmOfferRequest request) {
        CompanyAccess.requireCompanyId(currentUser, CompanyPermission.TRANSFER_CREATE);
        return ResponseEntity.ok(executionService.confirmRequote(currentUser, executionId, request));
    }
}
