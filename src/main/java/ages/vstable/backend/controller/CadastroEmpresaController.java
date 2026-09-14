package ages.vstable.backend.controller;

import ages.vstable.backend.dto.empresa.CadastroEmpresaRequest;
import ages.vstable.backend.dto.empresa.CadastroEmpresaResponse;
import ages.vstable.backend.service.CadastroEmpresaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
@Tag(name = "Cadastro do representante - Empresa")
public class CadastroEmpresaController {

    private final CadastroEmpresaService cadastroEmpresaService;

    @PostMapping("/{progressoCadastroId}/empresa")
    @Operation(summary = "Cadastra a empresa e avança o onboarding para compliance")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Empresa criada e vinculada"),
            @ApiResponse(responseCode = "400", description = "Payload malformado ou campo obrigatório inválido"),
            @ApiResponse(responseCode = "404", description = "Progresso de cadastro não encontrado"),
            @ApiResponse(responseCode = "409", description = "CNPJ duplicado ou progresso em estado incompatível"),
            @ApiResponse(responseCode = "422", description = "CNPJ ou CEP em formato inválido"),
            @ApiResponse(responseCode = "500", description = "Falha interna; alterações revertidas")
    })
    public ResponseEntity<CadastroEmpresaResponse> create(
            @Parameter(description = "Token UUID retornado na etapa de acesso", required = true)
            @PathVariable UUID progressoCadastroId,
            @Valid @RequestBody CadastroEmpresaRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(cadastroEmpresaService.create(progressoCadastroId, request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Progresso de cadastro não encontrado"));
    }
}
