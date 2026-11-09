package ages.vstable.backend.service;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real o vínculo empresarial: o mapeamento das entidades contra a migration V11,
 * a atribuição atômica do proprietário no onboarding e o contexto de acesso devolvido por
 * {@code GET /v1/users/me} pela cadeia completa de segurança. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyMembershipIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private SyntheticDataSeeder syntheticDataSeeder;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private CompanyInvitationRepository invitationRepository;

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM import_transactions");
        jdbcTemplate.update("DELETE FROM export_transactions");
        jdbcTemplate.update("DELETE FROM base_transactions");
        jdbcTemplate.update("DELETE FROM beneficiaries");
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM administrators");
    }

    @Test
    void onboarding_createsOwnerMembershipTogetherWithCompanyAndUser() {
        // Act
        OnboardingResponseDTO response = onboardingService.performOnboarding(onboardingRequest());

        // Assert
        CompanyMembershipEntity membership = membershipRepository
                .findFirstByUserIdAndStatusOrderByRemovedAtDesc(response.getUserId(), MembershipStatus.ACTIVE)
                .orElseThrow();
        assertThat(membership.getCompanyId()).isEqualTo(response.getCompanyId());
        assertThat(membership.getRole()).isEqualTo(CompanyRole.OWNER);
        assertThat(membership.getVersion()).isEqualTo(1);
        assertThat(membership.getJoinedAt()).isNotNull();
        assertThat(membership.getRemovedAt()).isNull();
    }

    @Test
    void onboarding_whenOwnerMembershipCannotBeSaved_leavesNeitherCompanyNorUser() {
        // Arrange: o banco rejeita qualquer vínculo novo.
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_membership_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'membership insert rejected by the test'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_membership_insert BEFORE INSERT ON company_memberships
                FOR EACH ROW EXECUTE FUNCTION reject_membership_insert()
                """);

        try {
            // Act / Assert
            assertThatThrownBy(() -> onboardingService.performOnboarding(onboardingRequest()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_membership_insert ON company_memberships");
            jdbcTemplate.execute("DROP FUNCTION reject_membership_insert()");
        }

        assertThat(count("companies")).isZero();
        assertThat(count("users")).isZero();
        assertThat(count("avenia_kyc_verifications")).isZero();
        assertThat(count("company_memberships")).isZero();
    }

    @Test
    void currentUser_afterOnboarding_returnsOwnerAccessWithoutSecrets() throws Exception {
        // Arrange
        String token = onboardViaHttp();

        // Act / Assert
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountType").value("USER"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"))
                .andExpect(jsonPath("$.companyAccess.status").value("ACTIVE"))
                .andExpect(jsonPath("$.companyAccess.version").value(1))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(9))
                .andExpect(jsonPath("$.companyAccess.permissions[8]").value("MEMBERS_MANAGE"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash"))))
                .andExpect(content().string(not(containsString("twoFactorSecret"))));
        String companyId = jdbcTemplate.queryForObject("SELECT company_id::text FROM company_memberships", String.class);
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.companyId").value(companyId))
                .andExpect(jsonPath("$.companyAccess.companyId").value(companyId));
    }

    @Test
    void currentUser_withTheSameToken_reflectsRoleChangeAndRemovalWithoutNewLogin() throws Exception {
        // Arrange
        String token = onboardViaHttp();

        // Act / Assert: perfil alterado depois da emissão do token.
        jdbcTemplate.update("UPDATE company_memberships SET role = 'OPERATOR', version = 2");
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyAccess.role").value("OPERATOR"))
                .andExpect(jsonPath("$.companyAccess.version").value(2))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(7));

        // Act / Assert: vínculo removido depois da emissão do token.
        jdbcTemplate.update("UPDATE company_memberships SET status = 'REMOVED', removed_at = now(), version = 3");
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyAccess.status").value("REMOVED"))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(1))
                .andExpect(jsonPath("$.companyAccess.permissions[0]").value("SELF_MEMBERSHIP_READ"));
    }

    @Test
    void currentUser_withoutAnyMembership_keepsTheCompanyOfTheAccountAndHasNoCompanyAccess() throws Exception {
        // Arrange
        UUID companyId = insertCompany("1");
        UUID userId = insertUser(companyId, "legado@example.com");
        UserEntity user = UserEntity.builder().id(userId).email("legado@example.com").build();

        // Act / Assert
        mockMvc.perform(get("/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenUtils.generateToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.companyAccess").doesNotExist());
    }

    @Test
    void currentUser_administrator_hasNoCompanyNorCompanyAccess() throws Exception {
        // Arrange
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdministratorEntity admin = administratorRepository.save(AdministratorEntity.builder()
                .fullName("Admin V-Stable")
                .email("admin@vstable.test")
                .passwordHash("hash")
                .passwordSalt("salt")
                .createdAt(now)
                .updatedAt(now)
                .build());

        // Act / Assert
        mockMvc.perform(get("/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenUtils.generateToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountType").value("ADMIN"))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyAccess").doesNotExist());
    }

    @Test
    void syntheticSeed_createsTheDevelopmentUserAsOwnerOfItsCompany() {
        // Act
        syntheticDataSeeder.seed("Dev-Seed-Pass-1!", false);

        // Assert
        assertThat(jdbcTemplate.queryForList("""
                SELECT m.role || ':' || m.status || ':' || (m.company_id = u.company_id)
                FROM company_memberships m JOIN users u ON u.id = m.user_id
                WHERE u.email = 'dev.user@example.test'
                """, String.class)).containsExactly("OWNER:ACTIVE:true");
    }

    @Test
    void membershipRepository_returnsTheActiveMembershipAndTheMostRecentlyRemovedOne() {
        // Arrange
        UUID companyId = insertCompany("1");
        UUID userId = insertUser(companyId, "membro@example.com");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        saveMembership(companyId, userId, CompanyRole.OPERATOR, MembershipStatus.REMOVED, now.minusDays(2));
        CompanyMembershipEntity recentlyRemoved =
                saveMembership(companyId, userId, CompanyRole.VIEWER, MembershipStatus.REMOVED, now.minusDays(1));
        CompanyMembershipEntity active =
                saveMembership(companyId, userId, CompanyRole.OPERATOR, MembershipStatus.ACTIVE, null);

        // Act / Assert
        assertThat(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.ACTIVE))
                .get().extracting(CompanyMembershipEntity::getId).isEqualTo(active.getId());
        assertThat(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.REMOVED))
                .get().extracting(CompanyMembershipEntity::getId).isEqualTo(recentlyRemoved.getId());
    }

    @Test
    void invitationRepository_persistsPendingInvitationWithRecoverableToken() {
        // Arrange
        UUID companyId = insertCompany("1");
        UUID creatorId = insertUser(companyId, "dono@example.com");

        // Act
        CompanyInvitationEntity saved = invitationRepository.saveAndFlush(
                invitation(companyId, creatorId, InvitationStatus.PENDING, "ciphertext", "v1"));

        // Assert
        CompanyInvitationEntity loaded = invitationRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(loaded.getRole()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getTokenCiphertext()).isEqualTo("ciphertext");
        assertThat(loaded.getTokenKeyVersion()).isEqualTo("v1");
        assertThat(loaded.getAcceptedAt()).isNull();
        assertThat(loaded.getRevokedAt()).isNull();
    }

    @Test
    void invitationRepository_rejectsAcceptedInvitationThatKeepsTheRecoverableToken() {
        // Arrange
        UUID companyId = insertCompany("1");
        UUID creatorId = insertUser(companyId, "dono@example.com");

        // Act / Assert
        assertThatThrownBy(() -> invitationRepository.saveAndFlush(
                invitation(companyId, creatorId, InvitationStatus.ACCEPTED, "ciphertext", "v1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String onboardViaHttp() throws Exception {
        String body = mockMvc.perform(post("/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Joao da Silva","email":"joao@example.com","password":"Senha@123",
                                 "confirmPassword":"Senha@123","legalName":"Empresa Exemplo Ltda",
                                 "cnpj":"11.222.333/0001-81","country":"Brasil","zipCode":"90000-000",
                                 "city":"Porto Alegre","state":"RS"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private OnboardingRequestDTO onboardingRequest() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setFullName("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setPassword("Senha@123");
        request.setConfirmPassword("Senha@123");
        request.setLegalName("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }

    private UUID insertCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UUID insertUser(UUID companyId, String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, company_id, full_name, email, password_hash, password_salt)
                VALUES (?, ?, 'User', ?, 'h', 's')
                """, id, companyId, email);
        return id;
    }

    private CompanyMembershipEntity saveMembership(UUID companyId, UUID userId, CompanyRole role,
                                                   MembershipStatus status, OffsetDateTime removedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return membershipRepository.saveAndFlush(CompanyMembershipEntity.builder()
                .companyId(companyId)
                .userId(userId)
                .role(role)
                .status(status)
                .joinedAt(now.minusDays(30))
                .removedAt(removedAt)
                .updatedAt(now)
                .build());
    }

    private CompanyInvitationEntity invitation(UUID companyId, UUID creatorId, InvitationStatus status,
                                               String ciphertext, String keyVersion) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return CompanyInvitationEntity.builder()
                .companyId(companyId)
                .name("Ana Souza")
                .email("ana@example.com")
                .role(CompanyRole.OPERATOR)
                .status(status)
                .tokenHash("a".repeat(64))
                .tokenCiphertext(ciphertext)
                .tokenKeyVersion(keyVersion)
                .createdBy(creatorId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
