package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaBeneficiaryService;
import ages.vstable.backend.external.avenia.AveniaGateway;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ExportTransactionRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.service.BeneficiaryService;
import ages.vstable.backend.service.CompanyFinancialAccountService;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.TwoFactorAuthService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * backend#76 pela cadeia HTTP: segurança, controllers, {@link TransferService}, {@link BeneficiaryService} e
 * {@link CompanyFinancialAccountService} reais; só a persistência e o {@link AveniaGateway} são simulados,
 * então nenhuma chamada sai para a Avenia. Verifica que a conta Avenia vem da empresa do vínculo ativo,
 * nunca do usuário, do KYC individual, de outra empresa ou da conta principal da API key.
 */
@WebMvcTest({TransferController.class, BeneficiaryController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class,
        TransferService.class, BeneficiaryService.class,
        CompanyFinancialAccountService.class, AveniaTransferService.class, AveniaBeneficiaryService.class,
        CompanyFinancialAccountControllerTest.PackagePrivateServices.class})
class CompanyFinancialAccountControllerTest {

    /** BeneficiaryValidator é package-private em service; registrado pelo nome, sem mudar sua visibilidade. */
    @TestConfiguration
    @ComponentScan(basePackages = "ages.vstable.backend.service", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "ages\\.vstable\\.backend\\.service\\.BeneficiaryValidator"))
    static class PackagePrivateServices {
    }

    private static final String COMPANY_A_SUB_ACCOUNT = "company-a-sub-account";

    private static final String QUOTE_BODY = """
            {"amount":100.00,"amountType":"SOURCE","sourceCurrency":"BRL","destinationCurrency":"USDC",
             "paymentMethod":"PIX","beneficiaryId":"%s"}
            """;

    private static final String WALLET_BENEFICIARY_BODY = """
            {"beneficiaryType":"LEGAL_ENTITY","legalName":"Fornecedor Teste Ltda",
             "identificationDocument":"11222333000181","country":"Brasil","address":"Rua Teste, 123",
             "receivingMethod":"CRYPTO_WALLET","nickname":"Carteira","isSelfCustody":true,
             "counterpartyName":"Fornecedor","counterpartyCountry":"BR","walletAddress":"0xABCDEF1234567890",
             "blockchainNetwork":"polygon","currency":"USDC","confirmed":true}
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private AveniaGateway aveniaGateway;

    @MockitoBean
    private ProviderAccountRepository providerAccountRepository;

    @MockitoBean
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @MockitoBean
    private BeneficiaryRepository beneficiaryRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private BaseTransactionRepository baseTransactionRepository;

    @MockitoBean
    private ImportTransactionRepository importTransactionRepository;

    @MockitoBean
    private ExportTransactionRepository exportTransactionRepository;

    @MockitoBean
    private TwoFactorAuthService twoFactorAuthService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    private final UUID companyA = UUID.randomUUID();
    private final UUID companyB = UUID.randomUUID();
    private BeneficiaryEntity beneficiaryOfA;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        beneficiaryOfA = walletBeneficiary(companyA, COMPANY_A_SUB_ACCOUNT);
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryOfA.getId(), companyA))
                .thenReturn(Optional.of(beneficiaryOfA));
        when(companyRepository.findById(companyA)).thenReturn(Optional.of(approvedCompany(companyA)));
        when(companyRepository.findById(companyB)).thenReturn(Optional.of(approvedCompany(companyB)));
    }

    // ------------------------------------------------------------------ mesma empresa

    @Test
    void ownerAndOperatorOfTheSameCompany_quoteOnTheSameCompanyAccount() throws Exception {
        // Arrange
        stubAveniaAccount(companyA, "ACTIVE", COMPANY_A_SUB_ACCOUNT);
        when(aveniaGateway.createQuote(any())).thenReturn(validQuote());

        // Act
        mockMvc.perform(quote(TestCompanyUsers.member(companyA, CompanyRole.OWNER))).andExpect(status().isOk());
        mockMvc.perform(quote(TestCompanyUsers.member(companyA, CompanyRole.OPERATOR))).andExpect(status().isOk());

        // Assert
        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaGateway, times(2)).createQuote(captor.capture());
        assertThat(captor.getAllValues()).extracting(AveniaQuoteRequest::subAccountId)
                .containsExactly(COMPANY_A_SUB_ACCOUNT, COMPANY_A_SUB_ACCOUNT);
        verify(aveniaKycVerificationRepository, never()).findFirstByUserIdOrderByCreatedAtDesc(any());
    }

    @Test
    void ownerAndOperatorOfTheSameCompany_registerBeneficiariesOnTheSameCompanyAccount() throws Exception {
        // Arrange
        stubAveniaAccount(companyA, "ACTIVE", COMPANY_A_SUB_ACCOUNT);
        when(aveniaGateway.createWalletBeneficiary(any())).thenReturn(new AveniaBeneficiaryResponse(UUID.randomUUID()));
        when(beneficiaryRepository.saveAndFlush(any())).thenAnswer(call -> {
            BeneficiaryEntity saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // Act
        mockMvc.perform(createBeneficiary(companyA, TestCompanyUsers.member(companyA, CompanyRole.OWNER)))
                .andExpect(status().isCreated());
        mockMvc.perform(createBeneficiary(companyA, TestCompanyUsers.member(companyA, CompanyRole.OPERATOR)))
                .andExpect(status().isCreated());

        // Assert
        ArgumentCaptor<AveniaWalletBeneficiaryRequest> captor = ArgumentCaptor.forClass(AveniaWalletBeneficiaryRequest.class);
        verify(aveniaGateway, times(2)).createWalletBeneficiary(captor.capture());
        assertThat(captor.getAllValues()).extracting(AveniaWalletBeneficiaryRequest::subAccountId)
                .containsExactly(COMPANY_A_SUB_ACCOUNT, COMPANY_A_SUB_ACCOUNT);
        verify(aveniaKycVerificationRepository, never()).findFirstByUserIdOrderByCreatedAtDesc(any());
    }

    // ------------------------------------------------------------------ outra empresa

    @Test
    void memberOfAnotherCompany_cannotQuoteWithABeneficiaryOfTheOtherCompany() throws Exception {
        // Arrange: B tenta usar o beneficiário de A; a conta de A nunca é resolvida para B.
        stubAveniaAccount(companyA, "ACTIVE", COMPANY_A_SUB_ACCOUNT);

        // Act / Assert
        mockMvc.perform(quote(TestCompanyUsers.member(companyB, CompanyRole.OWNER)))
                .andExpect(status().isNotFound());
        verify(providerAccountRepository, never()).findByCompanyIdAndProvider(any(), any());
        verifyNoInteractions(aveniaGateway);
    }

    @Test
    void memberOfAnotherCompany_cannotRegisterBeneficiaryByChangingTheCompanyInTheRoute() throws Exception {
        // Act / Assert
        mockMvc.perform(createBeneficiary(companyA, TestCompanyUsers.member(companyB, CompanyRole.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        verifyNoInteractions(aveniaGateway, providerAccountRepository);
    }

    // ------------------------------------------------------------------ perfis e vínculo

    @Test
    void viewer_cannotQuoteNorRegisterBeneficiaries() throws Exception {
        // Arrange
        UserEntity viewer = TestCompanyUsers.member(companyA, CompanyRole.VIEWER);

        // Act / Assert
        mockMvc.perform(quote(viewer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(createBeneficiary(companyA, viewer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(aveniaGateway, providerAccountRepository);
    }

    @Test
    void removedMember_losesAccessToTheCompanyAccount() throws Exception {
        // Arrange
        UserEntity removed = TestCompanyUsers.removedMember(companyA, CompanyRole.OWNER);

        // Act / Assert
        mockMvc.perform(quote(removed))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(createBeneficiary(companyA, removed))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        verifyNoInteractions(aveniaGateway, providerAccountRepository);
    }

    @Test
    void platformAdministrator_doesNotInheritTheCompanyAccount() throws Exception {
        // Arrange
        var administrator = User.withUsername("admin@example.com").password("password").roles("ADMIN").build();

        // Act / Assert
        mockMvc.perform(post("/v1/transfers/quote").with(user(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUOTE_BODY.formatted(beneficiaryOfA.getId())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(aveniaGateway, providerAccountRepository);
    }

    // ------------------------------------------------------------------ conta empresarial inválida

    @Test
    void companyWithoutAveniaAccount_isBlockedWithoutFallbackToTheMainApiKeyAccount() throws Exception {
        // Arrange: nenhuma associação AVENIA; o usuário até teria KYC individual, que não deve ser usado.
        UserEntity owner = TestCompanyUsers.member(companyA, CompanyRole.OWNER);

        // Act / Assert
        mockMvc.perform(quote(owner))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));
        mockMvc.perform(createBeneficiary(companyA, owner))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));
        verifyNoInteractions(aveniaGateway);
        verify(beneficiaryRepository, never()).saveAndFlush(any());
        verify(aveniaKycVerificationRepository, never()).findFirstByUserIdOrderByCreatedAtDesc(any());
    }

    @Test
    void companyAccountNotActive_isBlocked() throws Exception {
        // Arrange
        stubAveniaAccount(companyA, "PENDING", COMPANY_A_SUB_ACCOUNT);

        // Act / Assert
        mockMvc.perform(quote(TestCompanyUsers.member(companyA, CompanyRole.OPERATOR)))
                .andExpect(status().is(422));
        verifyNoInteractions(aveniaGateway);
    }

    @Test
    void legacyIndividualAssociation_isBlockedWithoutExposingTheProviderIdentifier() throws Exception {
        // Arrange: a associação da empresa aponta para a subconta INDIVIDUAL do KYC de um membro.
        stubAveniaAccount(companyA, "ACTIVE", "individual-kyc-sub-account");
        when(aveniaKycVerificationRepository.existsByAveniaSubAccountId("individual-kyc-sub-account")).thenReturn(true);

        // Act / Assert
        mockMvc.perform(quote(TestCompanyUsers.member(companyA, CompanyRole.OWNER)))
                .andExpect(status().is(422))
                .andExpect(content().string(not(containsString("individual-kyc-sub-account"))));
        verifyNoInteractions(aveniaGateway);
    }

    @Test
    void providerFailure_isReportedAsProviderErrorWithoutFalseSuccess() throws Exception {
        // Arrange
        stubAveniaAccount(companyA, "ACTIVE", COMPANY_A_SUB_ACCOUNT);
        when(aveniaGateway.createQuote(any()))
                .thenThrow(AveniaIntegrationException.response("temporarily unavailable", 503, null));

        // Act / Assert
        mockMvc.perform(quote(TestCompanyUsers.member(companyA, CompanyRole.OPERATOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString(COMPANY_A_SUB_ACCOUNT))));
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder quote(UserEntity principal) {
        return post("/v1/transfers/quote").with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(QUOTE_BODY.formatted(beneficiaryOfA.getId()));
    }

    private MockHttpServletRequestBuilder createBeneficiary(UUID routeCompanyId, UserEntity principal) {
        return post("/v1/companies/" + routeCompanyId + "/beneficiaries").with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(WALLET_BENEFICIARY_BODY);
    }

    private void stubAveniaAccount(UUID companyId, String status, String subAccountId) {
        OffsetDateTime now = OffsetDateTime.now();
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.AVENIA))
                .thenReturn(Optional.of(ProviderAccountEntity.builder()
                        .id(UUID.randomUUID())
                        .company(CompanyEntity.builder().id(companyId).build())
                        .provider(IntegrationProvider.AVENIA)
                        .status(status)
                        .externalCustomerId(subAccountId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build()));
    }

    private static CompanyEntity approvedCompany(UUID id) {
        CompanyEntity company = CompanyEntity.builder().id(id).legalName("Empresa").build();
        company.setKybStatus(ComplianceStatus.APPROVED);
        return company;
    }

    private static BeneficiaryEntity walletBeneficiary(UUID companyId, String subAccountId) {
        return BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(approvedCompany(companyId))
                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                .aveniaWalletId(UUID.randomUUID())
                .aveniaSubAccountId(subAccountId)
                .blockchainNetwork(BlockchainNetwork.polygon)
                .walletAddress("0xabc123")
                .currency("USDC")
                .build();
    }

    private static AveniaQuoteResponse validQuote() {
        return new AveniaQuoteResponse(
                "quote-token", "BRL", "PIX", new BigDecimal("100.00"),
                "USDC", "POLYGON", new BigDecimal("19.00"),
                BigDecimal.ZERO, "BRL", false, false, List.of(),
                new BigDecimal("5.20"), "BRLUSDC", "IMMEDIATE");
    }
}
