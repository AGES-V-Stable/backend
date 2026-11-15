package ages.vstable.backend.controller;

import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycRequest;
import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycStatus;
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
}
