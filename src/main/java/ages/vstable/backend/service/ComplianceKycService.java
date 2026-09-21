package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComplianceKycService {

    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaClient aveniaClient;

    public Optional<KycSubmitResponse> finalizar(UUID kycVerificationId, KycSubmitRequest request) {
        return aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    if (kyc.getDocumentId() == null || kyc.getDocumentId().isBlank()) {
                        throw new UnprocessableEntityException(
                                "Upload do documento de identidade ainda não foi concluído");
                    }
                    if (kyc.getLivenessId() == null || kyc.getLivenessId().isBlank()) {
                        throw new UnprocessableEntityException(
                                "Verificação de liveness ainda não foi concluída");
                    }

                    AveniaKycResponse aveniaResponse =
                            aveniaClient.finalizarKyc(request, kyc.getDocumentId(), kyc.getLivenessId());

                    kyc.setAveniaProcessId(aveniaResponse.getId());
                    aveniaKycVerificationRepository.save(kyc);

                    KycSubmitResponse response = new KycSubmitResponse();
                    response.setAveniaProcessId(aveniaResponse.getId());
                    return response;
                });
    }
}
