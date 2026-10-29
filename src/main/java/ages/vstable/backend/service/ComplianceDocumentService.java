package ages.vstable.backend.service;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.compliance.DocumentSubmitRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartResponse;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComplianceDocumentService {

    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaClient aveniaClient;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;

    public Optional<DocumentUploadStartResponse> iniciar(UUID kycVerificationId, UUID currentUserId,
                                                        DocumentUploadStartRequest request) {
        Optional<DocumentUploadStartResponse> result = aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    KycOwnership.verify(kyc, currentUserId);
                    String subAccountId = subAccountProvisioningService.ensureSubAccountId(kyc);
                    AveniaDocumentUploadResponse aveniaResponse = aveniaClient.iniciarDocumento(
                            request.getDocumentType(), request.isDoubleSided(), subAccountId);

                    DocumentUploadStartResponse response = new DocumentUploadStartResponse();
                    response.setId(aveniaResponse.getId());
                    response.setUploadUrlFront(aveniaResponse.getUploadUrlFront());
                    response.setUploadUrlBack(aveniaResponse.getUploadUrlBack());
                    return response;
                });
        OperationalLog.milestone("document.upload.started", result.isPresent() ? "success" : "unavailable");
        return result;
    }

    public boolean concluir(UUID kycVerificationId, UUID currentUserId, DocumentSubmitRequest request) {
        boolean result = aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    KycOwnership.verify(kyc, currentUserId);
                    kyc.setDocumentId(request.getDocumentoId());
                    aveniaKycVerificationRepository.save(kyc);
                    return true;
                })
                .orElse(false);
        OperationalLog.milestone("document.submission.completed", result ? "success" : "unavailable");
        return result;
    }
}
