package ages.vstable.backend.controller;

import ages.vstable.backend.dto.onboarding.AccountRequestDTO;
import ages.vstable.backend.dto.onboarding.CompanyRegistrationRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.dto.onboarding.OnboardingStatusResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/onboarding")
@RequiredArgsConstructor
@Tag(name = "Representative and company onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping
    @Operation(summary = "Registers the representative and the company at once, before facial KYC (Avenia)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Representative and company registered; KYC verification created as pending"),
            @ApiResponse(responseCode = "400", description = "Missing required field"),
            @ApiResponse(responseCode = "409", description = "Email or CNPJ already registered"),
            @ApiResponse(responseCode = "422", description = "Password and confirmation mismatch, weak password, invalid email/CNPJ/zip code format"),
    })
    public ResponseEntity<OnboardingResponseDTO> create(@Valid @RequestBody OnboardingRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(onboardingService.performOnboarding(request));
    }

    @PostMapping("/account")
    @Operation(summary = "Creates only the access account; the company is registered later by the authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created without company; the token only reaches non-company routes"),
            @ApiResponse(responseCode = "400", description = "Missing required field"),
            @ApiResponse(responseCode = "409", description = "Email already registered"),
            @ApiResponse(responseCode = "422", description = "Password and confirmation mismatch or weak password"),
    })
    public ResponseEntity<OnboardingResponseDTO> createAccount(@Valid @RequestBody AccountRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.createAccount(request));
    }

    @PostMapping("/company")
    @Operation(summary = "Registers the company of the authenticated user without a company and makes the user its owner")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Company registered, owner membership and pending KYC created"),
            @ApiResponse(responseCode = "400", description = "Missing required field"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Authenticated account is not a company user"),
            @ApiResponse(responseCode = "409", description = "CNPJ already registered or the user already belongs to a company"),
            @ApiResponse(responseCode = "422", description = "Invalid CNPJ/zip code format"),
    })
    public ResponseEntity<OnboardingResponseDTO> registerCompany(
            @AuthenticationPrincipal UserEntity currentUser,
            @Valid @RequestBody CompanyRegistrationRequestDTO request) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.registerCompany(currentUser, request));
    }

    @GetMapping("/me")
    @Operation(summary = "Returns the latest KYC verification of the authenticated representative, so an interrupted registration can be resumed")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current onboarding state returned"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Authenticated account is not a company user"),
            @ApiResponse(responseCode = "404", description = "No KYC verification for this user"),
    })
    public ResponseEntity<OnboardingStatusResponse> current(@AuthenticationPrincipal UserEntity currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(onboardingService.getCurrentOnboarding(currentUser));
    }
}
