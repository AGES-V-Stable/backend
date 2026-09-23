package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessStatusResponse;
import ages.vstable.backend.dto.compliance.LivenessSubmitRequest;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComplianceLivenessService {

    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaClient aveniaClient;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;

    public Optional<LivenessStartResponse> iniciar(UUID kycVerificationId) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    String subAccountId = subAccountProvisioningService.ensureSubAccountId(kyc);
                    AveniaDocumentResponse aveniaResponse = aveniaClient.iniciarLiveness(subAccountId);

                    LivenessStartResponse response = new LivenessStartResponse();
                    response.setId(aveniaResponse.getId());
                    response.setSessionId(aveniaResponse.getSessionId());
                    response.setLivenessUrl(aveniaResponse.getLivenessUrl());
                    response.setValidateLivenessToken(aveniaResponse.getValidateLivenessToken());
                    return response;
                });
    }

    public Optional<LivenessStatusResponse> consultarStatus(UUID kycVerificationId, String livenessId) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    AveniaDocumentStatusResponse aveniaResponse =
                            aveniaClient.consultarStatusDocumento(livenessId, kyc.getAveniaSubAccountId());
                    AveniaDocumentStatusResponse.Document document = aveniaResponse.getDocument();

                    LivenessStatusResponse response = new LivenessStatusResponse();
                    response.setReady(document.isReady());
                    response.setStatus(document.getUploadStatusFront());
                    return response;
                });
    }

    public boolean concluir(UUID kycVerificationId, LivenessSubmitRequest request) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    kyc.setLivenessId(request.getLivenessId());
                    aveniaKycVerificationRepository.save(kyc);
                    return true;
                })
                .orElse(false);
    }
}
