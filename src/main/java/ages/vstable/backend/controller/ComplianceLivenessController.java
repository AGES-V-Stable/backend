package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessStatusResponse;
import ages.vstable.backend.dto.compliance.LivenessSubmitRequest;
import ages.vstable.backend.service.ComplianceLivenessService;
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
@Tag(name = "Onboarding - Compliance/Liveness")
public class ComplianceLivenessController {

    private final ComplianceLivenessService complianceLivenessService;

    @PostMapping("/{kycVerificationId}/compliance/liveness")
    @Operation(summary = "Inicia a verificação de liveness na Avenia e retorna o link de redirecionamento")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verificação de liveness iniciada, retorna id e link"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
    })
    public ResponseEntity<LivenessStartResponse> iniciar(@PathVariable UUID kycVerificationId) {
        return complianceLivenessService.iniciar(kycVerificationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{kycVerificationId}/compliance/liveness/status")
    @Operation(summary = "Consulta se a verificação de liveness já foi concluída na Avenia")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status consultado com sucesso"),
            @ApiResponse(responseCode = "400", description = "livenessId ausente ou vazio"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
    })
    public ResponseEntity<LivenessStatusResponse> consultarStatus(
            @PathVariable UUID kycVerificationId,
            @RequestParam(required = false) String livenessId) {

        if (livenessId == null || livenessId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return complianceLivenessService.consultarStatus(kycVerificationId, livenessId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{kycVerificationId}/compliance/liveness")
    @Operation(summary = "Registra o id de liveness concluído na verificação de KYC")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Liveness registrado com sucesso"),
            @ApiResponse(responseCode = "400", description = "livenessId ausente ou vazio"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
    })
    public ResponseEntity<Void> concluir(
            @PathVariable UUID kycVerificationId,
            @Valid @RequestBody LivenessSubmitRequest request) {

        boolean atualizado = complianceLivenessService.concluir(kycVerificationId, request);

        return atualizado
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.notFound().build();
    }
}
