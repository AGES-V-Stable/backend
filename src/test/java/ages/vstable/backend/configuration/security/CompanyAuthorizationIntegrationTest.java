package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa (filtro JWT, vínculo carregado a cada requisição,
 * controllers e handlers de erro), que a autorização segue o vínculo atual e não o token: perfis,
 * vínculo removido, outra empresa, administrador e token emitido antes da mudança de acesso.
 * Nenhum caminho "permitido" chama provedores externos. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyAuthorizationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String TRANSFER_BODY = """
            {"amount":100,"amountType":"SOURCE","destinationCurrency":"USD","paymentMethod":"PIX","beneficiaryId":"%s"}
            """.formatted(UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyA;
    private UUID companyB;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM base_transactions");
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM administrators");
        companyA = insertCompany("11111111000111");
        companyB = insertCompany("22222222000122");
    }

    @Test
    void everyActiveProfile_canReadBalanceCompanyAndBeneficiariesOfItsCompany() throws Exception {
        for (CompanyRole role : CompanyRole.values()) {
            // Arrange
            String token = tokenOf(companyA, role, MembershipStatus.ACTIVE);

            // Act / Assert
            mockMvc.perform(authorized(get("/v1/balance"), token)).andExpect(status().isOk());
            mockMvc.perform(authorized(get("/v1/companies/" + companyA), token)).andExpect(status().isOk());
            mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/beneficiaries"), token))
                    .andExpect(status().isOk());
            mockMvc.perform(authorized(get("/api/transferencias"), token)).andExpect(status().isOk());
            mockMvc.perform(authorized(get("/v1/dashboard/transaction-volume"), token)).andExpect(status().isOk());
        }
    }

    @Test
    void everyActiveProfile_readsTransferDetailsOfItsCompanyWithoutIndividualKyc() throws Exception {
        // Arrange: nenhum usuário tem KYC individual; a transferência pertence à empresa A.
        UUID transferId = insertTransfer(companyA);
        String path = "/api/transferencias/" + transferId;

        for (CompanyRole role : CompanyRole.values()) {
            // Act / Assert
            mockMvc.perform(authorized(get(path), tokenOf(companyA, role, MembershipStatus.ACTIVE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(transferId.toString()));
        }
        mockMvc.perform(authorized(get(path), tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        mockMvc.perform(authorized(get(path), tokenOf(companyA, CompanyRole.OPERATOR, MembershipStatus.REMOVED)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
    }

    @Test
    void viewer_cannotCreateTransfersEvenCallingTheApiDirectly() throws Exception {
        // Arrange
        String token = tokenOf(companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(authorized(post("/v1/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(authorized(post("/v1/transfers/quote"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void operator_passesTheAuthorizationOfTransfersAndIsStoppedByTheFinancialRules() throws Exception {
        // Arrange: o usuário não tem 2FA ativo, então a regra financeira existente recusa antes de qualquer provedor.
        String token = tokenOf(companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(authorized(post("/v1/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));
    }

    @Test
    void onlyTheOwner_canSubmitCompanyComplianceToTheProvider() throws Exception {
        for (CompanyRole role : new CompanyRole[]{CompanyRole.OPERATOR, CompanyRole.VIEWER}) {
            // Act / Assert
            mockMvc.perform(authorized(post("/v1/blindpay/compliance/terms"),
                            tokenOf(companyA, role, MembershipStatus.ACTIVE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }
    }

    @Test
    void removedMember_losesEveryCompanyRouteWithRevokedAccess() throws Exception {
        // Arrange
        String token = tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/api/transferencias"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/api/transferencias/" + UUID.randomUUID()), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(post("/v1/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
    }

    @Test
    void memberOfAnotherCompany_cannotTouchTheCompanyByChangingTheId() throws Exception {
        // Arrange
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA), ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/beneficiaries"), ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyB), ownerOfB)).andExpect(status().isOk());
    }

    @Test
    void userWithACompanyOnTheAccountButNoMembership_isDenied() throws Exception {
        // Arrange
        UserEntity user = insertUser(companyA);
        String token = jwtTokenUtils.generateToken(user);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
    }

    @Test
    void administrator_keepsItsOwnAccessAndDoesNotInheritAMembership() throws Exception {
        // Arrange
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdministratorEntity admin = administratorRepository.save(AdministratorEntity.builder()
                .fullName("Admin V-Stable").email("admin@vstable.test").passwordHash("h").passwordSalt("s")
                .createdAt(now).updatedAt(now).build());
        String token = jwtTokenUtils.generateToken(admin);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA), token)).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/beneficiaries"), token))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
    }

    @Test
    void tokenIssuedBeforeTheChange_followsTheCurrentMembershipOnTheNextRequests() throws Exception {
        // Arrange: o mesmo token de um operador, sem novo login.
        UserEntity user = insertUser(companyA);
        CompanyMembershipEntity membership = insertMembership(user, companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        String token = jwtTokenUtils.generateToken(user);
        mockMvc.perform(authorized(post("/v1/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));

        // Act: o perfil cai para visualizador.
        jdbcTemplate.update("UPDATE company_memberships SET role = 'VIEWER', version = version + 1 WHERE id = ?",
                membership.getId());

        // Assert
        mockMvc.perform(authorized(post("/v1/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(authorized(get("/v1/balance"), token)).andExpect(status().isOk());

        // Act: o vínculo é removido.
        jdbcTemplate.update("UPDATE company_memberships SET status = 'REMOVED', removed_at = now() WHERE id = ?",
                membership.getId());

        // Assert
        mockMvc.perform(authorized(get("/v1/balance"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String tokenOf(UUID companyId, CompanyRole role, MembershipStatus status) {
        UserEntity user = insertUser(companyId);
        insertMembership(user, companyId, role, status);
        return jwtTokenUtils.generateToken(user);
    }

    private UUID insertCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UUID insertTransfer(UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO base_transactions (id, company_id, status, foreign_currency, foreign_amount) "
                + "VALUES (?, ?, 'SETTLED', 'USD', 100.00)", id, companyId);
        return id;
    }

    private UserEntity insertUser(UUID companyId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return userRepository.saveAndFlush(UserEntity.builder()
                .companyId(companyId)
                .fullName("Usuario")
                .email(UUID.randomUUID() + "@example.com")
                .passwordHash("h")
                .passwordSalt("s")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private CompanyMembershipEntity insertMembership(UserEntity user, UUID companyId, CompanyRole role,
                                                     MembershipStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return membershipRepository.saveAndFlush(CompanyMembershipEntity.builder()
                .companyId(companyId)
                .userId(user.getId())
                .role(role)
                .status(status)
                .joinedAt(now)
                .removedAt(status == MembershipStatus.REMOVED ? now : null)
                .updatedAt(now)
                .build());
    }
}
