package ages.vstable.backend.controller;

import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    public ResponseEntity<CompanyResponse> create(
            @Valid @RequestBody CompanyCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(companyService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<CompanyResponse>> findAll() {
        return ResponseEntity.ok(companyService.findAll());
    }

}
