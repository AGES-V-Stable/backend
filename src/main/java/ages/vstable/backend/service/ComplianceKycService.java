package ages.vstable.backend.service;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttempt;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttemptsResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTermsResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finaliza o KYC Level 1 de forma idempotente: antes de submeter uma nova
 * tentativa para a Avenia, consulta o estado da tentativa mais recente da
 * subconta correspondente e só cria uma tentativa nova quando realmente não
 * existe nenhuma em andamento/aprovada bloqueando reenvio.
 *
 * Isso existe porque, sem subconta por verificação, toda chamada caía na conta
 * principal da API key — que, assim que aprovada uma vez, faz a Avenia
 * recusar QUALQUER submissão nova com "user already approved in level 1",
 * mesmo para representantes diferentes. Ver AveniaSubAccountProvisioningService.
 */
@Service
@RequiredArgsConstructor
public class ComplianceKycService {

    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaClient aveniaClient;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;

    /** Refresh an existing individual attempt without submitting or provisioning an account. */
    @Transactional
    public void refreshStatus(AveniaKycVerificationEntity kyc) {
        if (kyc.getAveniaProcessId() == null || kyc.getAveniaProcessId().isBlank()
                || kyc.getAveniaSubAccountId() == null || kyc.getAveniaSubAccountId().isBlank()) {
            return;
        }
        AveniaKycAttempt attempt = aveniaClient.consultarTentativa(
                kyc.getAveniaProcessId(), kyc.getAveniaSubAccountId());
        if (attempt == null || !kyc.getAveniaProcessId().equals(attempt.getId()) || attempt.getStatus() == null) {
            throw AveniaIntegrationException.communication("Resposta de KYC inválida da Avenia", null);
        }
        ComplianceStatus status = switch (attempt.getStatus()) {
            case "PENDING", "PROCESSING" -> ComplianceStatus.UNDER_REVIEW;
            case "COMPLETED" -> switch (attempt.getResult() == null ? "" : attempt.getResult()) {
                case "APPROVED" -> ComplianceStatus.APPROVED;
                case "REJECTED" -> ComplianceStatus.REJECTED;
                default -> throw AveniaIntegrationException.communication("Resultado de KYC inválido da Avenia", null);
            };
            case "EXPIRED" -> ComplianceStatus.PENDING;
            default -> throw AveniaIntegrationException.communication("Status de KYC inválido da Avenia", null);
        };
        if (kyc.getStatus() != status) {
            kyc.setStatus(status);
            aveniaKycVerificationRepository.save(kyc);
        }
    }

    public Optional<KycSubmitResponse> finalizar(UUID kycVerificationId, UUID currentUserId, KycSubmitRequest request) {
        Optional<KycSubmitResponse> result = aveniaKycVerificationRepository.findById(kycVerificationId)
                .map(kyc -> {
                    KycOwnership.verify(kyc, currentUserId);
                    return processar(kyc, request);
                });
        OperationalLog.milestone("kyc.submission.completed", result
                .map(value -> value.getStatus().name().toLowerCase(Locale.ROOT))
                .orElse("unavailable"));
        return result;
    }

    private KycSubmitResponse processar(AveniaKycVerificationEntity kyc, KycSubmitRequest request) {
        if (kyc.getStatus() == ComplianceStatus.APPROVED) {
            return toResponse(kyc, null);
        }

        String subAccountId = subAccountProvisioningService.ensureSubAccountId(kyc);

        AveniaKycAttempt tentativaAtual = buscarTentativaAtual(kyc, subAccountId);
        if (tentativaAtual != null) {
            Optional<KycSubmitResponse> resultadoSemResubmeter = aplicarTentativa(kyc, tentativaAtual);
            if (resultadoSemResubmeter.isPresent()) {
                return resultadoSemResubmeter.get();
            }
            // REJECTED retryable ou EXPIRED: segue para submeter uma tentativa nova.
        }

        if (kyc.getDocumentId() == null || kyc.getDocumentId().isBlank()) {
            throw new UnprocessableEntityException("Upload do documento de identidade ainda não foi concluído");
        }
        if (kyc.getLivenessId() == null || kyc.getLivenessId().isBlank()) {
            throw new UnprocessableEntityException("Verificação de liveness ainda não foi concluída");
        }

        // A Avenia exige que todo usuário final, inclusive de subconta, aceite os termos dela.
        AveniaTermsResponse terms = aveniaClient.getTerms(subAccountId);
        if (terms == null || !terms.accepted()) {
            throw new UnprocessableEntityException("AVENIA_TERMS_REQUIRED",
                    "Aceite os termos de uso e a política de privacidade da Avenia antes de enviar o KYC");
        }

        AveniaKycResponse aveniaResponse =
                aveniaClient.finalizarKyc(request, kyc.getDocumentId(), kyc.getLivenessId(), subAccountId);

        kyc.setAveniaProcessId(aveniaResponse.getId());
        kyc.setStatus(ComplianceStatus.UNDER_REVIEW);
        aveniaKycVerificationRepository.save(kyc);

        return toResponse(kyc, null);
    }

    /**
     * Busca a tentativa mais recente conhecida: se já sabemos o id de uma
     * tentativa anterior, consulta ela diretamente; senão, lista as tentativas
     * da subconta (cobre o caso de recuperar um estado que não foi persistido
     * por alguma falha anterior).
     */
    private AveniaKycAttempt buscarTentativaAtual(AveniaKycVerificationEntity kyc, String subAccountId) {
        if (kyc.getAveniaProcessId() != null && !kyc.getAveniaProcessId().isBlank()) {
            return aveniaClient.consultarTentativa(kyc.getAveniaProcessId(), subAccountId);
        }

        AveniaKycAttemptsResponse listagem = aveniaClient.listarTentativasKyc(subAccountId);
        List<AveniaKycAttempt> tentativas = listagem.getAttempts();
        return (tentativas == null || tentativas.isEmpty()) ? null : tentativas.get(0);
    }

    /**
     * Aplica o resultado de uma tentativa já existente ao registro local.
     * Retorna uma resposta (sem precisar submeter nada novo) quando o estado
     * já é definitivo o suficiente para não reenviar — aprovado, em análise,
     * ou rejeitado sem possibilidade de nova tentativa. Retorna vazio quando é
     * necessário submeter uma tentativa nova (rejeitado com retry permitido,
     * ou expirado).
     */
    private Optional<KycSubmitResponse> aplicarTentativa(AveniaKycVerificationEntity kyc, AveniaKycAttempt attempt) {
        kyc.setAveniaProcessId(attempt.getId());

        switch (attempt.getStatus()) {
            case "COMPLETED" -> {
                if ("APPROVED".equals(attempt.getResult())) {
                    kyc.setStatus(ComplianceStatus.APPROVED);
                    aveniaKycVerificationRepository.save(kyc);
                    return Optional.of(toResponse(kyc, attempt.getResultMessage()));
                }
                kyc.setStatus(ComplianceStatus.REJECTED);
                aveniaKycVerificationRepository.save(kyc);
                if (!attempt.isRetryable()) {
                    return Optional.of(toResponse(kyc, attempt.getResultMessage()));
                }
                return Optional.empty();
            }
            case "PENDING", "PROCESSING" -> {
                kyc.setStatus(ComplianceStatus.UNDER_REVIEW);
                aveniaKycVerificationRepository.save(kyc);
                return Optional.of(toResponse(kyc, null));
            }
            default -> {
                // EXPIRED (ou qualquer status desconhecido): permite nova tentativa.
                return Optional.empty();
            }
        }
    }


    private KycSubmitResponse toResponse(AveniaKycVerificationEntity kyc, String resultMessage) {
        KycSubmitResponse response = new KycSubmitResponse();
        response.setAveniaProcessId(kyc.getAveniaProcessId());
        response.setStatus(kyc.getStatus());
        response.setResultMessage(resultMessage);
        return response;
    }
}
