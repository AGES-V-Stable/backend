package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanyComplianceUpdateRequest;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanySummaryResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.service.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/companies")
@RequiredArgsConstructor
@Tag(name = "Companies")
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registers a new company (Admin only; clients register through /v1/onboarding)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Company registered successfully"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
            @ApiResponse(responseCode = "409", description = "CNPJ already registered"),
    })
    public ResponseEntity<CompanyResponse> create(
            @Valid @RequestBody CompanyCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(companyService.create(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lists all registered companies (Admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of companies returned successfully"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
    })
    public ResponseEntity<List<CompanyResponse>> findAll() {
        return ResponseEntity.ok(companyService.findAll());
    }

    @GetMapping("/summaries")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lists companies with overall compliance status and primary representative, for the admin client list (Admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Company summaries returned successfully"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
    })
    public ResponseEntity<List<CompanySummaryResponse>> findSummaries() {
        return ResponseEntity.ok(companyService.findSummaries());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Finds a company by its id (company members or Admin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Company found"),
            @ApiResponse(responseCode = "403", description = "Company belongs to another account"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
    })
    public ResponseEntity<CompanyResponse> findById(
            @PathVariable UUID id,
            Authentication authentication) {

        CompanyAccess.assertCanRead(authentication, id);

        return companyService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/compliance-status")
    @Operation(summary = "Retrieves the current compliance (KYB/AML) status of a company (company members or Admin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compliance status returned successfully"),
            @ApiResponse(responseCode = "403", description = "Company belongs to another account"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
            @ApiResponse(responseCode = "422", description = "Company has an invalid compliance status"),
    })
    public ResponseEntity<CompanyComplianceStatusResponse> getComplianceStatus(
            @PathVariable UUID id,
            Authentication authentication) {

        CompanyAccess.assertCanRead(authentication, id);

        return ResponseEntity.ok(companyService.getComplianceStatus(id));
    }

    @PatchMapping("/{id}/compliance-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Records the compliance review of a company (KYB and/or AML status) (Admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compliance status updated"),
            @ApiResponse(responseCode = "400", description = "No status informed or invalid status value"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
    })
    public ResponseEntity<CompanyResponse> updateComplianceStatus(
            @PathVariable UUID id,
            @RequestBody CompanyComplianceUpdateRequest request) {

        return ResponseEntity.ok(companyService.updateComplianceStatus(id, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Updates an existing company (Admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Company updated successfully"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
            @ApiResponse(responseCode = "409", description = "CNPJ already registered"),
    })
    public ResponseEntity<CompanyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CompanyUpdateRequest request) {

        if (!companyService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                companyService.update(id, request)
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deletes a company (Admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Company deleted successfully"),
            @ApiResponse(responseCode = "403", description = "Requires ADMIN"),
            @ApiResponse(responseCode = "404", description = "Company not found"),
    })
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {

        if (!companyService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        companyService.deleteById(id);

        return ResponseEntity.noContent().build();
    }
}
