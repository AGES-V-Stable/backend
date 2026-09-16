package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.ComplianceSubmissionResponse;
import ages.vstable.backend.service.ComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
@Tag(name = "Cadastro do representante - Compliance")
public class ComplianceController {

    private final ComplianceService complianceService;

    @PostMapping(value = "/{progressoCadastroId}/compliance", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Envia documentos e conclui a submissão do onboarding")
    public ResponseEntity<ComplianceSubmissionResponse> submit(
            @PathVariable UUID progressoCadastroId,
            @RequestParam("tipo_documento") String tipoDocumento,
            @RequestPart("documentos") List<MultipartFile> documentos) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(complianceService.submit(progressoCadastroId, tipoDocumento, documentos));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Progresso de cadastro não encontrado"));
    }
}
