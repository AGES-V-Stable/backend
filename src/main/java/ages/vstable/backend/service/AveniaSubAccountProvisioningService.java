package ages.vstable.backend.service;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaSubAccountResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Garante que cada verificação de KYC tenha sua própria subconta INDIVIDUAL na
 * Avenia antes de qualquer chamada de documento/liveness/KYC. Sem isso, todas as
 * chamadas caem na conta principal da API key, que fica aprovada permanentemente
 * assim que o primeiro KYC é aprovado — daí o bug de "user already approved in
 * level 1" sendo devolvido para representantes completamente diferentes.
 */
@Service
@RequiredArgsConstructor
public class AveniaSubAccountProvisioningService {

    private final AveniaClient aveniaClient;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;

    public String ensureSubAccountId(AveniaKycVerificationEntity kyc) {
        if (kyc.getAveniaSubAccountId() != null && !kyc.getAveniaSubAccountId().isBlank()) {
            return kyc.getAveniaSubAccountId();
        }

        AveniaSubAccountResponse response = aveniaClient.criarSubconta("kyc-" + kyc.getId());
        kyc.setAveniaSubAccountId(response.getId());
        aveniaKycVerificationRepository.save(kyc);
        return response.getId();
    }
}
