package ages.vstable.backend.controller;

import ages.vstable.backend.dto.representante.AcessoCreateRequest;
import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.service.AcessoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
public class AcessoController {

    private final AcessoService acessoService;

    @PostMapping("/representante/acesso")
    public ResponseEntity<AcessoResponse> create(
            @RequestBody AcessoCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(acessoService.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AcessoResponse> findById(@PathVariable UUID id) {
        return acessoService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
