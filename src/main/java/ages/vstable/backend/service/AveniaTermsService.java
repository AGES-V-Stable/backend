package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.AveniaTermsStatusResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaTermsAcceptRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTermsResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.logging.OperationalLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aceite dos termos de uso e da política de privacidade da Avenia pela subconta do
 * titular (GET/POST /v2/terms/{subAccountId}). A Avenia exige que todo usuário final,
 * inclusive de subconta, aceite os documentos depois de vê-los.
 */
@Service
@RequiredArgsConstructor
public class AveniaTermsService {

    static final String CHANNEL = "API";

    private final AveniaKycVerificationRepository kycRepository;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;
    private final AveniaClient aveniaClient;

    public Optional<AveniaTermsStatusResponse> status(UUID kycVerificationId, UUID currentUserId) {
        return kycRepository.findById(kycVerificationId).map(kyc -> {
            KycOwnership.verify(kyc, currentUserId);
            return toResponse(currentTerms(kyc));
        });
    }

    /**
     * Registra o aceite da versão exibida ao usuário. Um token novo é obtido no momento
     * do aceite; se a Avenia publicou outra versão nesse meio-tempo, o aceite é recusado.
     */
    public Optional<AveniaTermsStatusResponse> accept(UUID kycVerificationId, UUID currentUserId, int version) {
        return kycRepository.findById(kycVerificationId).map(kyc -> {
            KycOwnership.verify(kyc, currentUserId);
            AveniaTermsResponse terms = currentTerms(kyc);
            if (terms.accepted()) {
                return toResponse(terms);
            }
            if (terms.currentVersion() == null || terms.currentVersion() != version) {
                throw new ConflictException("TERMS_VERSION_MISMATCH",
                        "Os termos da Avenia foram atualizados. Revise a versão vigente e aceite novamente.");
            }
            aveniaClient.acceptTerms(kyc.getAveniaSubAccountId(), new AveniaTermsAcceptRequest(
                    version, terms.acceptanceToken(), CHANNEL,
                    // Evidência do consentimento coletado pela V-Stable, sem dados pessoais.
                    Map.of("consentId", kyc.getId().toString(), "source", "v-stable-onboarding")));
            OperationalLog.milestone("kyc.avenia_terms.accepted", "success");
            return new AveniaTermsStatusResponse(version, true, toResponse(terms).documents());
        });
    }

    private AveniaTermsResponse currentTerms(AveniaKycVerificationEntity kyc) {
        String subAccountId = subAccountProvisioningService.ensureSubAccountId(kyc);
        AveniaTermsResponse terms = aveniaClient.getTerms(subAccountId);
        if (terms == null || terms.currentVersion() == null) {
            // Resposta sem os termos: tratada como falha temporária do provedor.
            throw new AveniaIntegrationException("A Avenia não retornou os termos vigentes", null, true, null);
        }
        return terms;
    }

    private static AveniaTermsStatusResponse toResponse(AveniaTermsResponse terms) {
        return new AveniaTermsStatusResponse(terms.currentVersion(), terms.accepted(), terms.documents().stream()
                .filter(document -> document.contentUrl() != null && document.contentUrl().startsWith("https://"))
                .map(document -> new AveniaTermsStatusResponse.Document(
                        document.documentKey(), document.displayName(), document.contentUrl()))
                .toList());
    }
}
