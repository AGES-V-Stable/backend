package ages.vstable.backend.service;

import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
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
import java.util.UUID;

/**
 * The first financial operation binds the active owner's approved individual KYC subaccount to the company.
 * All authorized members share that persisted association, which survives owner changes and failed operations.
 * Existing associations are never replaced or automatically reactivated.
 */
@Service
@RequiredArgsConstructor
public class CompanyFinancialAccountService {

    public static final String AVENIA_ACCOUNT_ACTIVE = "ACTIVE";

    private static final String UNAVAILABLE =
            "A empresa não possui conta financeira válida na Avenia; a operação foi bloqueada";
    private static final String OWNER_KYC_REQUIRED =
            "O proprietário da empresa precisa concluir o KYC com uma subconta Avenia aprovada";
    private static final String ALREADY_LINKED =
            "A subconta Avenia do proprietário já está vinculada a outra empresa";

    private final ProviderAccountRepository providerAccountRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository membershipRepository;

    // Commit the canonical association independently of a later provider or transfer failure.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String requireAveniaSubAccountId(UUID companyId) {
        if (companyId == null) {
            throw new ForbiddenException("COMPANY_ACCESS_DENIED", "Você não tem acesso a esta empresa.");
        }
        var existing = providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA);
        if (existing.isPresent()) return requireActive(existing.get());

        // Lock the company even when no account row exists yet; concurrent members must bind only once.
        var company = companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        existing = providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA);
        if (existing.isPresent()) return requireActive(existing.get());

        var owner = membershipRepository.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE).stream()
                .filter(member -> member.getRole() == CompanyRole.OWNER)
                .min(Comparator.comparing(CompanyMembershipEntity::getJoinedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyMembershipEntity::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new UnprocessableEntityException(OWNER_KYC_REQUIRED));
        var kyc = aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(owner.getUserId())
                .filter(verification -> verification.getStatus() == ComplianceStatus.APPROVED)
                .orElseThrow(() -> new UnprocessableEntityException(OWNER_KYC_REQUIRED));
        String subAccountId = kyc.getAveniaSubAccountId();
        if (subAccountId == null || subAccountId.isBlank()) {
            throw new UnprocessableEntityException(OWNER_KYC_REQUIRED);
        }
        if (providerAccountRepository.findByProviderAndExternalCustomerId(IntegrationProvider.AVENIA, subAccountId)
                .isPresent()) {
            throw new UnprocessableEntityException(ALREADY_LINKED);
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            providerAccountRepository.saveAndFlush(ProviderAccountEntity.builder()
                    .company(company).provider(IntegrationProvider.AVENIA).externalCustomerId(subAccountId)
                    .status(AVENIA_ACCOUNT_ACTIVE).createdAt(now).updatedAt(now).build());
        } catch (DataIntegrityViolationException ex) {
            // Different companies can race to bind the same individual account; the unique index decides.
            if (String.valueOf(ex.getMostSpecificCause().getMessage())
                    .contains("provider_accounts_provider_external_customer_key")) {
                throw new UnprocessableEntityException(ALREADY_LINKED);
            }
            throw ex;
        }
        return subAccountId;
    }

    private String requireActive(ProviderAccountEntity account) {
        String subAccountId = account.getExternalCustomerId();
        if (!AVENIA_ACCOUNT_ACTIVE.equals(account.getStatus()) || subAccountId == null || subAccountId.isBlank()) {
            throw new UnprocessableEntityException(UNAVAILABLE);
        }
        return subAccountId;
    }
}
