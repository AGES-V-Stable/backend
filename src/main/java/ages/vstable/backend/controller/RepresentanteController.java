package ages.vstable.backend.controller;

import ages.vstable.backend.dto.representante.RepresentanteCreateRequest;
import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.service.RepresentanteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/representantes")
@RequiredArgsConstructor
public class RepresentanteController {

    private final RepresentanteService representanteService;

    @PostMapping
    public ResponseEntity<RepresentanteResponse> create(
            @RequestBody RepresentanteCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(representanteService.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepresentanteResponse> findById(
            @PathVariable UUID id) {

        return representanteService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
