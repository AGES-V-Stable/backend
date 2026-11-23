package ages.vstable.backend.controller;

import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycRequest;
import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycStatus;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentUploadResponse;
import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.dto.blindpay.BlindPayRfiSubmitRequest;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.service.blindpay.BlindPayIndividualKycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/v1/blindpay/kyc")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class BlindPayIndividualKycController {
    private final BlindPayIndividualKycService service;

    @GetMapping
    public BlindPayIndividualKycStatus status(@AuthenticationPrincipal UserEntity user) {
        return service.status(user);
    }

    @PostMapping("/terms")
    public BlindPayInitiateTosResponse terms(@AuthenticationPrincipal UserEntity user) {
        return service.startTerms(user);
    }

    public record TermsReturnRequest(
            @jakarta.validation.constraints.NotNull java.util.UUID state,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "to_[A-Za-z0-9]{12}") String tosId) {}

    @PostMapping("/terms/confirm")
    public BlindPayIndividualKycStatus confirmTerms(@AuthenticationPrincipal UserEntity user,
            @RequestBody @Valid TermsReturnRequest request) {
        return service.confirmTerms(user, request.state(), request.tosId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BlindPayIndividualKycStatus submit(
            @AuthenticationPrincipal UserEntity user,
            @RequestPart("profile") @Valid BlindPayIndividualKycRequest profile,
            @RequestPart("idFront") MultipartFile idFront,
            @RequestPart(value = "idBack", required = false) MultipartFile idBack,
            @RequestPart("selfie") MultipartFile selfie,
            @RequestPart(value = "proofAddress", required = false) MultipartFile proofAddress
    ) throws IOException {
        return service.submit(user, profile, idFront, idBack, selfie, proofAddress);
    }

    /** Recomeça um KYC recusado: a BlindPay exige um novo cliente com novo aceite dos termos. */
    @PostMapping("/restart")
    public BlindPayIndividualKycStatus restart(@AuthenticationPrincipal UserEntity user) {
        return service.restart(user);
    }

    @GetMapping("/rfi")
    public BlindPayRfiDetailsResponse rfi(@AuthenticationPrincipal UserEntity user) {
        return service.rfi(user);
    }

    @PostMapping("/rfi")
    public BlindPayIndividualKycStatus submitRfi(
            @AuthenticationPrincipal UserEntity user,
            @RequestBody @Valid BlindPayRfiSubmitRequest request) {
        return service.submitRfi(user, request.responses());
    }

    @PostMapping(value = "/rfi/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BlindPayDocumentUploadResponse uploadRfiDocument(
            @AuthenticationPrincipal UserEntity user,
            @RequestPart("file") MultipartFile file) throws IOException {
        return service.uploadRfiDocument(user, file);
    }
}
