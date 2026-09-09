package ages.vstable.backend.controller;

import ages.vstable.backend.dto.empresa.EmpresaCreateRequest;
import ages.vstable.backend.dto.empresa.EmpresaResponse;
import ages.vstable.backend.dto.empresa.EmpresaUpdateRequest;
import ages.vstable.backend.dto.empresa.SituacaoCadastralResponse;
import ages.vstable.backend.service.EmpresaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/empresas")
@RequiredArgsConstructor
public class EmpresaController {

    private final EmpresaService empresaService;

    @PostMapping
    public ResponseEntity<EmpresaResponse> create(
            @Valid @RequestBody EmpresaCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(empresaService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<EmpresaResponse>> findAll() {
        return ResponseEntity.ok(empresaService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmpresaResponse> findById(
            @PathVariable UUID id) {

        return empresaService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmpresaResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody EmpresaUpdateRequest request) {

        if (!empresaService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                empresaService.update(id, request)
        );
    }

    @GetMapping("/{id}/situacao-cadastral")
    public ResponseEntity<SituacaoCadastralResponse> getSituacaoCadastral(
            @PathVariable UUID id) {

        return ResponseEntity.ok(empresaService.getSituacaoCadastral(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {

        if (!empresaService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        empresaService.deleteById(id);

        return ResponseEntity.noContent().build();
    }
}
