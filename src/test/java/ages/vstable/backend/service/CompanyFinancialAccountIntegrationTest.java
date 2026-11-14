package ages.vstable.backend.service;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * backend#76 no PostgreSQL real (schema do Flyway): a associação Avenia da empresa em {@code provider_accounts}
 * é a única fonte da conta usada por OWNER e OPERATOR; associação ausente ou apontando para uma subconta de KYC
 * individual bloqueia. O {@link AveniaClient} é simulado: nenhuma chamada sai para a Avenia. Ignorado sem Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyFinancialAccountIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String WALLET_BENEFICIARY_BODY = """
            {"beneficiaryType":"LEGAL_ENTITY","legalName":"Fornecedor Teste Ltda",
             "identificationDocument":"11222333000181","country":"Brasil","address":"Rua Teste, 123",
             "receivingMethod":"CRYPTO_WALLET","nickname":"Carteira","isSelfCustody":true,
             "counterpartyName":"Fornecedor","counterpartyCountry":"BR","walletAddress":"0xABCDEF1234567890",
             "blockchainNetwork":"polygon","currency":"USDC","confirmed":true}
            """;

    @MockitoBean
    private AveniaClient aveniaClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyA;

    @BeforeEach
    void cleanDatabase() {
        reset(aveniaClient);
        jdbcTemplate.update("DELETE FROM avenia_kyc_verifications");
        jdbcTemplate.update("DELETE FROM companies");
        companyA = insertApprovedCompany("11111111000111");
    }

    @Test
    void ownerAndOperator_registerBeneficiariesOnTheCompanyAccountStoredInProviderAccounts() throws Exception {
        // Arrange
        insertAveniaAccount(companyA, "ACTIVE", "company-a-sub-account");
        when(aveniaClient.createWalletBeneficiary(any())).thenReturn(new AveniaBeneficiaryResponse(UUID.randomUUID()));
        String owner = tokenOf(companyA, CompanyRole.OWNER);
        String operator = tokenOf(companyA, CompanyRole.OPERATOR);

        // Act
        mockMvc.perform(createBeneficiary(owner)).andExpect(status().isCreated());
        mockMvc.perform(createBeneficiary(operator)).andExpect(status().isCreated());

        // Assert
        ArgumentCaptor<AveniaWalletBeneficiaryRequest> captor = ArgumentCaptor.forClass(AveniaWalletBeneficiaryRequest.class);
        verify(aveniaClient, times(2)).createWalletBeneficiary(captor.capture());
        assertThat(captor.getAllValues()).extracting(AveniaWalletBeneficiaryRequest::subAccountId)
                .containsOnly("company-a-sub-account");
        List<String> persisted = jdbcTemplate.queryForList(
                "SELECT avenia_sub_account_id FROM beneficiaries WHERE company_id = ?", String.class, companyA);
        assertThat(persisted).containsExactly("company-a-sub-account", "company-a-sub-account");
        verify(aveniaClient, never()).criarSubconta(any());
    }

    @Test
    void companyWithoutAveniaAccount_isBlockedEvenWhenTheMemberHasAnIndividualKyc() throws Exception {
        // Arrange
        UserEntity owner = insertMember(companyA, CompanyRole.OWNER);
        insertKyc(owner.getId(), "owner-individual-sub-account");

        // Act / Assert
        mockMvc.perform(createBeneficiary(jwtTokenUtils.generateToken(owner))).andExpect(status().is(422));
        verify(aveniaClient, never()).createWalletBeneficiary(any());
        verify(aveniaClient, never()).criarSubconta(any());
        assertThat(beneficiaryCount()).isZero();
    }

    @Test
    void legacyAssociationPointingToAnIndividualKycSubAccount_isBlocked() throws Exception {
        // Arrange
        UserEntity owner = insertMember(companyA, CompanyRole.OWNER);
        insertKyc(owner.getId(), "owner-individual-sub-account");
        insertAveniaAccount(companyA, "ACTIVE", "owner-individual-sub-account");

        // Act / Assert
        mockMvc.perform(createBeneficiary(jwtTokenUtils.generateToken(owner))).andExpect(status().is(422));
        verify(aveniaClient, never()).createWalletBeneficiary(any());
        assertThat(beneficiaryCount()).isZero();
    }

    private MockHttpServletRequestBuilder createBeneficiary(String token) {
        return post("/v1/companies/" + companyA + "/beneficiaries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(WALLET_BENEFICIARY_BODY);
    }

    private Integer beneficiaryCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM beneficiaries", Integer.class);
    }

    private UUID insertApprovedCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO companies (id, legal_name, cnpj, kyb_status) VALUES (?, 'Acme', ?, 'APPROVED')", id, cnpj);
        return id;
    }

    private void insertAveniaAccount(UUID companyId, String status, String subAccountId) {
        jdbcTemplate.update(
                "INSERT INTO provider_accounts (company_id, provider, external_customer_id, status) "
                        + "VALUES (?, 'AVENIA', ?, ?)", companyId, subAccountId, status);
    }

    private void insertKyc(UUID userId, String subAccountId) {
        jdbcTemplate.update(
                "INSERT INTO avenia_kyc_verifications (user_id, avenia_sub_account_id, status) "
                        + "VALUES (?, ?, 'APPROVED')", userId, subAccountId);
    }

    private String tokenOf(UUID companyId, CompanyRole role) {
        return jwtTokenUtils.generateToken(insertMember(companyId, role));
    }

    private UserEntity insertMember(UUID companyId, CompanyRole role) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                .companyId(companyId)
                .fullName("Usuario")
                .email(UUID.randomUUID() + "@example.com")
                .passwordHash("h")
                .passwordSalt("s")
                .createdAt(now)
                .updatedAt(now)
                .build());
        membershipRepository.saveAndFlush(CompanyMembershipEntity.builder()
                .companyId(companyId)
                .userId(user.getId())
                .role(role)
                .status(MembershipStatus.ACTIVE)
                .joinedAt(now)
                .updatedAt(now)
                .build());
        return user;
    }
}
