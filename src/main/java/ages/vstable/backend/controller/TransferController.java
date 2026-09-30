package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Cotação e criação de transferências via Avenia")
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/quote")
    @Operation(summary = "Consulta uma cotação atualizada para exibição, sem criar nenhuma transferência")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cotação obtida com sucesso"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente ou inválido"),
            @ApiResponse(responseCode = "422", description = "Moeda não informada e não determinável, usuário sem KYC associado, ou rejeição de negócio da Avenia"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
            @ApiResponse(responseCode = "503", description = "Falha temporária ao comunicar com a Avenia; pode tentar novamente"),
    })
    public ResponseEntity<TransferQuoteResponse> quote(
            @Valid @RequestBody TransferQuoteRequest request,
            @AuthenticationPrincipal UserEntity currentUser) {
        UUID currentUserId = currentUser == null ? null : currentUser.getId();
        return ResponseEntity.ok(transferService.quote(request, currentUserId));
    }

    @PostMapping
    @Operation(summary = "Gera uma cotação nova e cria a transferência correspondente na Avenia")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transferência criada; retorna o status atual"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente ou inválido"),
            @ApiResponse(responseCode = "404", description = "Beneficiário não encontrado"),
            @ApiResponse(responseCode = "422", description = "Moeda não informada e não determinável, usuário sem KYC associado, ou rejeição de negócio da Avenia"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
            @ApiResponse(responseCode = "503", description = "Falha temporária ao comunicar com a Avenia; pode tentar novamente"),
    })
    public ResponseEntity<CreateTransferResponse> create(
            @Valid @RequestBody CreateTransferRequest request,
            @AuthenticationPrincipal UserEntity currentUser) {
        UUID currentUserId = currentUser == null ? null : currentUser.getId();
        return ResponseEntity.ok(transferService.create(request, currentUserId));
    }
}
