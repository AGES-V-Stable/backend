package ages.vstable.backend.controller;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
