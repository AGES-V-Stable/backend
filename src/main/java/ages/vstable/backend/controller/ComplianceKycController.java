package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.service.ComplianceKycService;
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
@Tag(name = "Onboarding - Compliance/KYC")
public class ComplianceKycController {

    private final ComplianceKycService complianceKycService;

    @PostMapping("/{kycVerificationId}/compliance/kyc")
    @Operation(summary = "Finaliza o KYC enviando os dados pessoais do representante direto para a Avenia (não persistidos no nosso banco)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KYC enviado com sucesso, retorna o id do processo na Avenia"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente"),
            @ApiResponse(responseCode = "404", description = "kycVerificationId inválido ou inexistente"),
            @ApiResponse(responseCode = "422", description = "Documento ou liveness ainda não concluídos"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
    })
    public ResponseEntity<KycSubmitResponse> finalizar(
            @PathVariable UUID kycVerificationId,
            @Valid @RequestBody KycSubmitRequest request) {

        return complianceKycService.finalizar(kycVerificationId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.notFound().build();
    }
}
