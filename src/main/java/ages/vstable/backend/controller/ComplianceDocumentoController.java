package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.DocumentSubmitRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartResponse;
import ages.vstable.backend.service.ComplianceDocumentoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestController
@RequestMapping("/v1/onboarding")
@RequiredArgsConstructor
@Tag(name = "Onboarding - Compliance/Documento")
public class ComplianceDocumentoController {

    private final ComplianceDocumentoService complianceDocumentoService;

    @PostMapping("/{kycVerificationId}/compliance/documento")
    @Operation(summary = "Inicia o upload do documento de identidade na Avenia e retorna as URLs de upload")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upload iniciado, retorna id e URL(s) de upload"),
            @ApiResponse(responseCode = "400", description = "documentType inválido"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
    })
    public ResponseEntity<DocumentUploadStartResponse> iniciar(
            @PathVariable UUID kycVerificationId,
            @Valid @RequestBody DocumentUploadStartRequest request) {

        return complianceDocumentoService.iniciar(kycVerificationId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{kycVerificationId}/compliance/documento")
    @Operation(summary = "Registra o id do documento de identidade enviado na verificação de KYC")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Documento registrado com sucesso"),
            @ApiResponse(responseCode = "400", description = "documentoId ausente ou vazio"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
    })
    public ResponseEntity<Void> concluir(
            @PathVariable UUID kycVerificationId,
            @Valid @RequestBody DocumentSubmitRequest request) {

        boolean atualizado = complianceDocumentoService.concluir(kycVerificationId, request);

        return atualizado
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.notFound().build();
    }
}
