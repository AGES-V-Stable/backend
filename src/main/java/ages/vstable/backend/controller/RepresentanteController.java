package ages.vstable.backend.controller;

import ages.vstable.backend.dto.representante.RepresentanteCreateRequest;
import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.service.RepresentanteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/representantes")
@RequiredArgsConstructor
public class RepresentanteController {

    private final RepresentanteService representanteService;

    @PostMapping
    public ResponseEntity<RepresentanteResponse> create(
            @Valid @RequestBody RepresentanteCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(representanteService.create(request));
    }
}
