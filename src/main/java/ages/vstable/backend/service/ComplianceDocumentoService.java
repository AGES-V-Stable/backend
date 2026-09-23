package ages.vstable.backend.service;

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
public class ComplianceDocumentoService {

    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaClient aveniaClient;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;

    public Optional<DocumentUploadStartResponse> iniciar(UUID kycVerificationId, DocumentUploadStartRequest request) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    String subAccountId = subAccountProvisioningService.ensureSubAccountId(kyc);
                    AveniaDocumentUploadResponse aveniaResponse = aveniaClient.iniciarDocumento(
                            request.getDocumentType(), request.isDoubleSided(), subAccountId);

                    DocumentUploadStartResponse response = new DocumentUploadStartResponse();
                    response.setId(aveniaResponse.getId());
                    response.setUploadUrlFront(aveniaResponse.getUploadUrlFront());
                    response.setUploadUrlBack(aveniaResponse.getUploadUrlBack());
                    return response;
                });
    }

    public boolean concluir(UUID kycVerificationId, DocumentSubmitRequest request) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    kyc.setDocumentId(request.getDocumentoId());
                    aveniaKycVerificationRepository.save(kyc);
                    return true;
                })
                .orElse(false);
    }
}
