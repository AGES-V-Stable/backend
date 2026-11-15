package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.entity.BlindPayIndividualKycEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.repository.BlindPayIndividualKycRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlindPayIndividualAccountServiceTest {
    private static final String CUSTOMER_ID = "re_000000000001";

    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private BlindPayIndividualKycRepository individualKycRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private CompanyMembershipRepository membershipRepository;
    @InjectMocks private BlindPayIndividualAccountService service;

    private CompanyEntity company;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ownerId = UUID.randomUUID();
    }

    @Test
    void existingCompanyAccountIsReturnedWithoutBinding() {
        var existing = ProviderAccountEntity.builder().company(company).provider(IntegrationProvider.BLINDPAY).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(existing));

        assertThat(service.accountFor(company.getId())).isSameAs(existing);
        verify(providerAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void approvedOwnerKycBecomesTheCompanyAccount() {
        givenNoAccountAndOwner();
        when(individualKycRepository.findById(ownerId)).thenReturn(Optional.of(kyc("approved", "clear", true)));
        when(providerAccountRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        ProviderAccountEntity account = service.accountFor(company.getId());

        ArgumentCaptor<ProviderAccountEntity> saved = ArgumentCaptor.forClass(ProviderAccountEntity.class);
        verify(providerAccountRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCompany()).isSameAs(company);
        assertThat(account.getExternalCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.APPROVED.name());
        assertThat(account.getTosId()).isEqualTo("to_000000000001");
    }

    @Test
    void ownerKycWithoutClearAmlOrTermsIsNotBound() {
        givenNoAccountAndOwner();
        when(individualKycRepository.findById(ownerId)).thenReturn(Optional.of(kyc("approved", null, true)));

        assertThatThrownBy(() -> service.accountFor(company.getId()))
                .isInstanceOf(BlindPayComplianceException.class)
                .hasMessage(BlindPayIndividualAccountService.OWNER_KYC_REQUIRED);
        verify(providerAccountRepository, never()).saveAndFlush(any());

        assertThat(BlindPayIndividualAccountService.isApproved(kyc("approved", "clear", false))).isFalse();
        assertThat(BlindPayIndividualAccountService.isApproved(kyc("verifying", "clear", true))).isFalse();
        assertThat(BlindPayIndividualAccountService.isApproved(kyc("approved_rfi", "clear", true))).isTrue();
    }

    @Test
    void ownerCustomerAlreadyUsedByAnotherCompanyIsRejected() {
        givenNoAccountAndOwner();
        when(individualKycRepository.findById(ownerId)).thenReturn(Optional.of(kyc("approved", "clear", true)));
        when(providerAccountRepository.findByProviderAndExternalCustomerId(IntegrationProvider.BLINDPAY, CUSTOMER_ID))
                .thenReturn(Optional.of(ProviderAccountEntity.builder().build()));

        assertThatThrownBy(() -> service.accountFor(company.getId()))
                .isInstanceOf(BlindPayComplianceException.class)
                .hasMessage(BlindPayIndividualAccountService.ALREADY_LINKED);
    }

    @Test
    void companyWithoutOwnerIsRejected() {
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(companyRepository.findByIdForUpdate(company.getId())).thenReturn(Optional.of(company));
        when(membershipRepository.findByCompanyIdAndStatus(company.getId(), MembershipStatus.ACTIVE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.accountFor(company.getId()))
                .isInstanceOf(BlindPayComplianceException.class)
                .hasMessage(BlindPayIndividualAccountService.OWNER_KYC_REQUIRED);
    }

    private void givenNoAccountAndOwner() {
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(companyRepository.findByIdForUpdate(company.getId())).thenReturn(Optional.of(company));
        when(membershipRepository.findByCompanyIdAndStatus(company.getId(), MembershipStatus.ACTIVE))
                .thenReturn(List.of(CompanyMembershipEntity.builder()
                        .id(UUID.randomUUID()).userId(ownerId).role(CompanyRole.OWNER).build()));
    }

    private BlindPayIndividualKycEntity kyc(String kycStatus, String amlStatus, boolean tosAccepted) {
        return BlindPayIndividualKycEntity.builder()
                .userId(ownerId)
                .customerId(CUSTOMER_ID)
                .tosId("to_000000000001")
                .tosAcceptedAt(tosAccepted ? OffsetDateTime.now() : null)
                .kycStatus(kycStatus)
                .amlStatus(amlStatus)
                .build();
    }
}
