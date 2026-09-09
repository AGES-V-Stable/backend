package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
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
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
@Tag(name = "Cadastro do representante - Compliance/Liveness")
public class ComplianceLivenessController {

    private final ComplianceLivenessService complianceLivenessService;

    @PostMapping("/{progressoCadastroId}/compliance/liveness")
    @Operation(summary = "Inicia a verificação de liveness na Avenia e retorna o link de redirecionamento (etapa 4b)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verificação de liveness iniciada, retorna id e link"),
            @ApiResponse(responseCode = "404", description = "progressoCadastroId inválido ou inexistente"),
            @ApiResponse(responseCode = "502", description = "Falha ao comunicar com a Avenia"),
    })
    public ResponseEntity<LivenessStartResponse> iniciar(@PathVariable UUID progressoCadastroId) {
        return complianceLivenessService.iniciar(progressoCadastroId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{progressoCadastroId}/compliance/liveness")
    @Operation(summary = "Registra o id de liveness concluído no progresso de cadastro (etapa 4b)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Liveness registrado com sucesso"),
            @ApiResponse(responseCode = "400", description = "livenessId ausente ou vazio"),
            @ApiResponse(responseCode = "404", description = "progressoCadastroId inválido ou inexistente"),
    })
    public ResponseEntity<Void> concluir(
            @PathVariable UUID progressoCadastroId,
            @Valid @RequestBody LivenessSubmitRequest request) {

        boolean atualizado = complianceLivenessService.concluir(progressoCadastroId, request);

        return atualizado
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.notFound().build();
    }
}
