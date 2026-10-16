package ages.vstable.backend.controller;

import ages.vstable.backend.dto.blindpay.BlindPayComplianceStatusResponse;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentUploadResponse;
import ages.vstable.backend.dto.blindpay.BlindPayKybRequest;
import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.dto.blindpay.BlindPayRfiSubmitRequest;
import ages.vstable.backend.dto.blindpay.BlindPayTermsResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.blindpay.BlindPayComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Onboarding da empresa do usuário autenticado na BlindPay. A empresa vem sempre do
 * token, nunca da requisição, então um usuário não acessa o compliance de outra.
 */
@RestController
@RequestMapping("/v1/blindpay/compliance")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "BlindPay - Compliance")
public class BlindPayComplianceController {

    private final BlindPayComplianceService complianceService;

    @GetMapping
    @Operation(summary = "Sincroniza com a BlindPay e retorna o status de compliance (KYB, AML e termos) da empresa")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status retornado; enabled=true somente quando aprovado"),
            @ApiResponse(responseCode = "404", description = "Empresa não encontrada"),
    })
    public ResponseEntity<BlindPayComplianceStatusResponse> status(@AuthenticationPrincipal UserEntity currentUser) {
        return ResponseEntity.ok(complianceService.getStatus(currentUser));
    }

    @PostMapping("/terms")
    @Operation(summary = "Inicia o aceite dos termos da BlindPay e retorna a URL da página hospedada")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "URL de aceite retornada"),
            @ApiResponse(responseCode = "409", description = "Termos já aceitos"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a BlindPay"),
    })
    public ResponseEntity<BlindPayTermsResponse> initiateTerms(@AuthenticationPrincipal UserEntity currentUser) {
        return ResponseEntity.ok(complianceService.initiateTermsOfService(currentUser));
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Envia um documento de compliance (PDF, JPEG ou PNG, até 3MB) e retorna a URL na BlindPay")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documento enviado"),
            @ApiResponse(responseCode = "400", description = "Arquivo vazio, de tipo não aceito ou documentType inválido"),
            @ApiResponse(responseCode = "413", description = "Arquivo maior que 3MB"),
            @ApiResponse(responseCode = "422", description = "Termos da BlindPay ainda não aceitos"),
    })
    public ResponseEntity<BlindPayDocumentUploadResponse> uploadDocument(
            @AuthenticationPrincipal UserEntity currentUser,
            @RequestParam BlindPayDocumentType documentType,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(complianceService.uploadDocument(currentUser, documentType, file.getBytes()));
    }

    @PostMapping("/kyb")
    @Operation(summary = "Envia o cadastro KYB da empresa (type=business, kyc_type=standard) para a BlindPay")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cadastro enviado; retorna o status atual"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente ou inválido"),
            @ApiResponse(responseCode = "409", description = "KYB já enviado"),
            @ApiResponse(responseCode = "422", description = "Termos não aceitos ou dados recusados pela BlindPay"),
            @ApiResponse(responseCode = "503", description = "BlindPay indisponível; o mesmo envio pode ser repetido"),
    })
    public ResponseEntity<BlindPayComplianceStatusResponse> submitKyb(
            @AuthenticationPrincipal UserEntity currentUser,
            @Valid @RequestBody BlindPayKybRequest request) {
        return ResponseEntity.ok(complianceService.submitKyb(currentUser, request));
    }

    @PutMapping("/kyb")
    @Operation(summary = "Atualiza o cadastro KYB já enviado; sócios devem informar o id da BlindPay")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cadastro atualizado; retorna o status atual"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente ou inválido"),
            @ApiResponse(responseCode = "409", description = "Empresa rejeitada; atualizar não reinicia a verificação"),
            @ApiResponse(responseCode = "422", description = "KYB ainda não enviado ou dados recusados pela BlindPay"),
    })
    public ResponseEntity<BlindPayComplianceStatusResponse> updateKyb(
            @AuthenticationPrincipal UserEntity currentUser,
            @Valid @RequestBody BlindPayKybRequest request) {
        return ResponseEntity.ok(complianceService.updateKyb(currentUser, request));
    }

    @GetMapping("/rfi")
    @Operation(summary = "Retorna o pedido de informações adicionais (RFI) aberto pela BlindPay")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "RFI retornado; open=false quando não há pendência"),
            @ApiResponse(responseCode = "422", description = "KYB ainda não enviado"),
    })
    public ResponseEntity<BlindPayRfiDetailsResponse> rfi(@AuthenticationPrincipal UserEntity currentUser) {
        return ResponseEntity.ok(complianceService.getRfi(currentUser));
    }

    @PostMapping("/rfi")
    @Operation(summary = "Responde o RFI aberto; a BlindPay volta a analisar o cadastro")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resposta enviada; retorna o status atual"),
            @ApiResponse(responseCode = "400", description = "Campo não solicitado, obrigatório ausente ou valor inválido"),
            @ApiResponse(responseCode = "409", description = "Não há RFI pendente"),
    })
    public ResponseEntity<BlindPayComplianceStatusResponse> submitRfi(
            @AuthenticationPrincipal UserEntity currentUser,
            @Valid @RequestBody BlindPayRfiSubmitRequest request) {
        return ResponseEntity.ok(complianceService.submitRfi(currentUser, request.responses()));
    }
}
