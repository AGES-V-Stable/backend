package ages.vstable.backend.service;

import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Titularidade financeira: resolve a conta do provedor pela empresa, nunca pelo usuário que opera.
 * O usuário autenticado é apenas o ator (autoria, 2FA, auditoria); dois membros autorizados da mesma
 * empresa sempre resolvem a mesma conta.
 *
 * <p>Na Avenia, a associação empresarial é a linha de {@code provider_accounts} da empresa com
 * {@code provider = AVENIA}. Esta classe não provisiona contas: o cadastro de subconta {@code COMPANY}
 * não está comprovado na integração atual, então a associação só existe depois de conciliada
 * (ver docs/avenia-conta-empresarial.md). Sem associação válida a operação é bloqueada — nunca há
 * fallback para a conta principal da API key, para a subconta individual de KYC de um membro ou
 * para a conta de outra empresa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyFinancialAccountService {

    /** Única situação em que a associação Avenia da empresa pode operar. */
    public static final String AVENIA_ACCOUNT_ACTIVE = "ACTIVE";

    private static final String UNAVAILABLE =
            "A empresa não possui conta financeira empresarial válida na Avenia; a operação foi bloqueada";

    private final ProviderAccountRepository providerAccountRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;

    /** Subconta Avenia da empresa, validada; lança exceção em vez de devolver uma conta não comprovada. */
    @Transactional(readOnly = true)
    public String requireAveniaSubAccountId(UUID companyId) {
        if (companyId == null) {
            throw new ForbiddenException("COMPANY_ACCESS_DENIED", "Você não tem acesso a esta empresa.");
        }
        ProviderAccountEntity account = providerAccountRepository
                .findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA)
                .orElseThrow(() -> new UnprocessableEntityException(UNAVAILABLE));
        String subAccountId = account.getExternalCustomerId();
        if (!AVENIA_ACCOUNT_ACTIVE.equals(account.getStatus()) || subAccountId == null || subAccountId.isBlank()) {
            throw new UnprocessableEntityException(UNAVAILABLE);
        }
        // Subconta criada para o KYC individual de um representante não é conta da empresa, mesmo que
        // alguém a tenha associado: a conversão INDIVIDUAL -> COMPANY nunca é presumida.
        if (aveniaKycVerificationRepository.existsByAveniaSubAccountId(subAccountId)) {
            log.warn("Avenia account of company {} points to an individual KYC sub-account; reconciliation required",
                    companyId);
            throw new UnprocessableEntityException(UNAVAILABLE);
        }
        return subAccountId;
    }
}
