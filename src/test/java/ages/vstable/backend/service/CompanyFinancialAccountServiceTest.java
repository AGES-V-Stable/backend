package ages.vstable.backend.service;

import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyFinancialAccountServiceTest {

    private static final String COMPANY_SUB_ACCOUNT = "company-sub-account";

    @Mock
    private ProviderAccountRepository providerAccountRepository;

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanyMembershipRepository membershipRepository;

    private CompanyFinancialAccountService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CompanyFinancialAccountService(providerAccountRepository, aveniaKycVerificationRepository, companyRepository, membershipRepository);
    }

    private ProviderAccountEntity aveniaAccount(UUID company, String status, String externalId) {
        OffsetDateTime now = OffsetDateTime.now();
        return ProviderAccountEntity.builder()
                .id(UUID.randomUUID())
                .company(CompanyEntity.builder().id(company).build())
                .provider(IntegrationProvider.AVENIA)
                .status(status)
                .externalCustomerId(externalId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void stubAccount(UUID company, ProviderAccountEntity account) {
        when(providerAccountRepository.findByCompanyIdAndProvider(company, IntegrationProvider.AVENIA))
                .thenReturn(Optional.ofNullable(account));
    }

    @Test
    void requireAveniaSubAccountId_contaEmpresarialAtiva_devolveSubcontaDaEmpresa() {
        // Arrange
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", COMPANY_SUB_ACCOUNT));

        // Act
        String subAccountId = service.requireAveniaSubAccountId(companyId);

        // Assert
        assertThat(subAccountId).isEqualTo(COMPANY_SUB_ACCOUNT);
    }

    @Test
    void requireAveniaSubAccountId_chamadasRepetidasDaMesmaEmpresa_resolvemSempreAMesmaConta() {
        // Arrange: o dono e o operador da empresa operam com o mesmo companyId vindo do vínculo.
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", COMPANY_SUB_ACCOUNT));

        // Act
        String ownerAccount = service.requireAveniaSubAccountId(companyId);
        String operatorAccount = service.requireAveniaSubAccountId(companyId);

        // Assert
        assertThat(ownerAccount).isEqualTo(operatorAccount).isEqualTo(COMPANY_SUB_ACCOUNT);
    }

    @Test
    void requireAveniaSubAccountId_empresasDistintas_resolvemCadaUmaSuaPropriaConta() {
        // Arrange
        UUID otherCompanyId = UUID.randomUUID();
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", COMPANY_SUB_ACCOUNT));
        stubAccount(otherCompanyId, aveniaAccount(otherCompanyId, "ACTIVE", "other-company-sub-account"));

        // Act / Assert
        assertThat(service.requireAveniaSubAccountId(companyId)).isEqualTo(COMPANY_SUB_ACCOUNT);
        assertThat(service.requireAveniaSubAccountId(otherCompanyId)).isEqualTo("other-company-sub-account");
    }

    @Test
    void requireAveniaSubAccountId_semProprietarioAtivo_bloqueiaSemCriarConta() {
        stubAccount(companyId, null);
        when(companyRepository.findByIdForUpdate(companyId)).thenReturn(Optional.of(CompanyEntity.builder().id(companyId).build()));

        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("proprietário");
        verify(providerAccountRepository, never()).saveAndFlush(any());
        verifyNoInteractions(aveniaKycVerificationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUSPENDED", "active", "FAILED"})
    void requireAveniaSubAccountId_associacaoNaoAtiva_bloqueia(String status) {
        // Arrange
        stubAccount(companyId, aveniaAccount(companyId, status, COMPANY_SUB_ACCOUNT));

        // Act / Assert
        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void requireAveniaSubAccountId_associacaoSemSubconta_bloqueiaEmVezDeUsarContaPrincipal(String externalId) {
        // Arrange: sem subAccountId a Avenia executaria na conta principal da API key.
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", externalId));

        // Act / Assert
        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class);
        verifyNoInteractions(aveniaKycVerificationRepository);
    }

    @Test
    void requireAveniaSubAccountId_vinculoIndividualExistentePermaneceSemConsultarNovoProprietario() {
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", "original-owner-sub"));

        assertThat(service.requireAveniaSubAccountId(companyId)).isEqualTo("original-owner-sub");

        verifyNoInteractions(aveniaKycVerificationRepository, membershipRepository, companyRepository);
        verify(providerAccountRepository, never()).saveAndFlush(any());
    }

    private AveniaKycVerificationEntity stubOwner(ComplianceStatus status, String subAccountId) {
        UUID userId = UUID.randomUUID();
        var company = CompanyEntity.builder().id(companyId).build();
        when(companyRepository.findByIdForUpdate(companyId)).thenReturn(Optional.of(company));
        when(membershipRepository.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE))
                .thenReturn(List.of(CompanyMembershipEntity.builder().id(UUID.randomUUID())
                        .companyId(companyId).userId(userId).role(CompanyRole.OWNER).status(MembershipStatus.ACTIVE).build()));
        var kyc = AveniaKycVerificationEntity.builder().userId(userId).status(status)
                .aveniaSubAccountId(subAccountId).build();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(kyc));
        return kyc;
    }

    @Test
    void requireAveniaSubAccountId_ownerApproved_createsAndReusesCanonicalAssociation() {
        stubOwner(ComplianceStatus.APPROVED, "owner-sub");
        java.util.concurrent.atomic.AtomicReference<ProviderAccountEntity> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(providerAccountRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return stored.get();
        });

        assertThat(service.requireAveniaSubAccountId(companyId)).isEqualTo("owner-sub");
        assertThat(service.requireAveniaSubAccountId(companyId)).isEqualTo("owner-sub");

        assertThat(stored.get().getCompany().getId()).isEqualTo(companyId);
        assertThat(stored.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(stored.get().getCreatedAt()).isNotNull();
        verify(providerAccountRepository).saveAndFlush(any());
        verify(membershipRepository).findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = ComplianceStatus.class, names = {"PENDING", "UNDER_REVIEW", "REJECTED"})
    void requireAveniaSubAccountId_ownerWithoutApprovedKyc_cannotBind(ComplianceStatus status) {
        stubOwner(status, "owner-sub");

        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId)).isInstanceOf(UnprocessableEntityException.class);

        verify(providerAccountRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void requireAveniaSubAccountId_approvedOwnerWithoutSubaccount_cannotUseMainAccount(String subAccountId) {
        stubOwner(ComplianceStatus.APPROVED, subAccountId);

        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId)).isInstanceOf(UnprocessableEntityException.class);

        verify(providerAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void requireAveniaSubAccountId_accountAlreadyBelongsToAnotherCompany_cannotBind() {
        stubOwner(ComplianceStatus.APPROVED, "owner-sub");
        when(providerAccountRepository.findByProviderAndExternalCustomerId(IntegrationProvider.AVENIA, "owner-sub"))
                .thenReturn(Optional.of(aveniaAccount(UUID.randomUUID(), "ACTIVE", "owner-sub")));

        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("outra empresa");

        verify(providerAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void requireAveniaSubAccountId_uniqueAccountRace_returnsBusinessErrorWithoutDatabaseDetails() {
        stubOwner(ComplianceStatus.APPROVED, "owner-sub");
        when(providerAccountRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "provider_accounts_provider_external_customer_key synthetic-private-details"));

        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("outra empresa")
                .hasMessageNotContaining("synthetic-private-details");
    }

    @Test
    void requireAveniaSubAccountId_semEmpresa_negaAcessoSemConsultarProvedor() {
        // Act / Assert
        assertThatThrownBy(() -> service.requireAveniaSubAccountId(null))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("COMPANY_ACCESS_DENIED");
        verify(providerAccountRepository, never()).findByCompanyIdAndProvider(any(), any());
        verifyNoInteractions(aveniaKycVerificationRepository);
    }
}
