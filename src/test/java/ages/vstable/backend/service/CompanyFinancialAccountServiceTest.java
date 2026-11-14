package ages.vstable.backend.service;

import ages.vstable.backend.entity.CompanyEntity;
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

    private CompanyFinancialAccountService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CompanyFinancialAccountService(providerAccountRepository, aveniaKycVerificationRepository);
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
    void requireAveniaSubAccountId_empresaSemAssociacao_bloqueiaSemFallbackParaOutraConta() {
        // Arrange
        stubAccount(companyId, null);

        // Act / Assert: só a conta da própria empresa é consultada; nenhuma outra conta (de outra empresa,
        // da API key ou do KYC individual de um membro) é procurada como alternativa.
        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("conta financeira empresarial");
        verify(providerAccountRepository).findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA);
        verifyNoMoreInteractions(providerAccountRepository);
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
    void requireAveniaSubAccountId_associacaoApontaParaSubcontaIndividualDeKyc_bloqueiaSemPresumirConversao() {
        // Arrange: associação legada/inconsistente com a subconta INDIVIDUAL do KYC de um membro.
        stubAccount(companyId, aveniaAccount(companyId, "ACTIVE", "individual-kyc-sub-account"));
        when(aveniaKycVerificationRepository.existsByAveniaSubAccountId("individual-kyc-sub-account"))
                .thenReturn(true);

        // Act / Assert
        assertThatThrownBy(() -> service.requireAveniaSubAccountId(companyId))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageNotContaining("individual-kyc-sub-account");
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
