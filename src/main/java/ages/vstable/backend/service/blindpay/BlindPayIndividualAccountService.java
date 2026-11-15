package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.entity.BlindPayIndividualKycEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BlindPayIndividualKycRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Conta BlindPay da empresa a partir do KYC individual do proprietário, no mesmo modelo
 * da Avenia ({@code CompanyFinancialAccountService}): a empresa opera com o cliente
 * pessoa física do dono, aprovado, com AML liberado e termos aceitos. O vínculo é feito
 * uma única vez e nunca substitui uma conta já existente (KYB ou individual).
 */
@Service
@RequiredArgsConstructor
public class BlindPayIndividualAccountService {

    static final String OWNER_KYC_REQUIRED =
            "O proprietário da empresa precisa concluir o KYC BlindPay aprovado";
    static final String ALREADY_LINKED =
            "O cliente BlindPay do proprietário já está vinculado a outra empresa";

    private final ProviderAccountRepository providerAccountRepository;
    private final BlindPayIndividualKycRepository individualKycRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository membershipRepository;

    /**
     * Retorna a conta BlindPay da empresa, vinculando o KYC aprovado do proprietário
     * quando a empresa ainda não tem nenhuma. Confirma o vínculo em transação própria,
     * independente de uma falha posterior na cotação.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProviderAccountEntity accountFor(UUID companyId) {
        Optional<ProviderAccountEntity> existing =
                providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY);
        if (existing.isPresent()) return existing.get();

        // Bloqueia a empresa: membros concorrentes só podem vincular uma vez.
        var company = companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        existing = providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY);
        if (existing.isPresent()) return existing.get();

        UUID ownerId = membershipRepository.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE).stream()
                .filter(member -> member.getRole() == CompanyRole.OWNER)
                .min(Comparator.comparing(CompanyMembershipEntity::getJoinedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyMembershipEntity::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(CompanyMembershipEntity::getUserId)
                .orElseThrow(() -> new BlindPayComplianceException(OWNER_KYC_REQUIRED));
        BlindPayIndividualKycEntity kyc = individualKycRepository.findById(ownerId)
                .filter(BlindPayIndividualAccountService::isApproved)
                .orElseThrow(() -> new BlindPayComplianceException(OWNER_KYC_REQUIRED));
        if (providerAccountRepository.findByProviderAndExternalCustomerId(
                IntegrationProvider.BLINDPAY, kyc.getCustomerId()).isPresent()) {
            throw new BlindPayComplianceException(ALREADY_LINKED);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            return providerAccountRepository.saveAndFlush(ProviderAccountEntity.builder()
                    .company(company)
                    .provider(IntegrationProvider.BLINDPAY)
                    .externalCustomerId(kyc.getCustomerId())
                    .status(BlindPayOnboardingStatus.APPROVED.name())
                    .kycStatus(kyc.getKycStatus())
                    .amlStatus(kyc.getAmlStatus())
                    .tosId(kyc.getTosId())
                    .tosAcceptedAt(kyc.getTosAcceptedAt())
                    .providerUpdatedAt(kyc.getProviderUpdatedAt())
                    .synchronizedAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Empresas diferentes podem disputar o mesmo cliente; o índice único decide.
            if (String.valueOf(ex.getMostSpecificCause().getMessage())
                    .contains("provider_accounts_provider_external_customer_key")) {
                throw new BlindPayComplianceException(ALREADY_LINKED);
            }
            throw ex;
        }
    }

    /** Mesma regra do KYB: só KYC aprovado, AML liberado e termos aceitos habilitam operações. */
    static boolean isApproved(BlindPayIndividualKycEntity kyc) {
        return kyc.getCustomerId() != null
                && BlindPayOnboardingStatus.fromProvider(kyc.getKycStatus(), kyc.getAmlStatus(),
                        kyc.getTosAcceptedAt() != null) == BlindPayOnboardingStatus.APPROVED;
    }
}
