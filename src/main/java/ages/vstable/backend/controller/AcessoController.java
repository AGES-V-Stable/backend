package ages.vstable.backend.controller;

import ages.vstable.backend.dto.representante.AcessoCreateRequest;
import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.service.AcessoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestController
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
@Tag(name = "Cadastro do representante - Acesso")
public class AcessoController {

    private final AcessoService acessoService;

    @PostMapping("/representante/acesso")
    @Operation(summary = "Cria a conta e o progresso de cadastro do representante (etapa 1 de 4)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Conta criada (ou reenvio idempotente reconhecido)"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente ou header Idempotency-Key ausente"),
            @ApiResponse(responseCode = "409", description = "E-mail já cadastrado, ou Idempotency-Key reutilizada com payload diferente"),
            @ApiResponse(responseCode = "422", description = "Senha e confirmação divergentes, senha fraca, ou e-mail em formato inválido"),
    })
    public ResponseEntity<AcessoResponse> create(
            @Parameter(description = "UUID v4 gerado pelo front por tentativa de submissão", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcessoCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(acessoService.create(idempotencyKey, request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recupera o progresso de cadastro pelo token (id do progresso_cadastro)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Progresso encontrado"),
            @ApiResponse(responseCode = "404", description = "Token inválido ou inexistente"),
    })
    public ResponseEntity<AcessoResponse> findById(@PathVariable UUID id) {
        return acessoService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.notFound().build();
    }
}
