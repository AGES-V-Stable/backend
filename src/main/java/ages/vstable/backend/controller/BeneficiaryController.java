package ages.vstable.backend.controller;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/companies/{companyId}/beneficiaries")
@RequiredArgsConstructor
@Tag(name = "Beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    @PostMapping
    @Operation(summary = "Registers a new beneficiary for a company")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Beneficiary registered successfully"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
            @ApiResponse(responseCode = "403", description = "Company is not verified"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
    })
    public ResponseEntity<BeneficiaryResponse> create(
            @PathVariable UUID companyId,
            @Valid @RequestBody BeneficiaryCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(beneficiaryService.create(companyId, request));
    }
}
