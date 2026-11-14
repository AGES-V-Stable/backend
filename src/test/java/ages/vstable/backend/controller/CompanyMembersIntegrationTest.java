package ages.vstable.backend.controller;

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
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokenCipher;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa de segurança, a listagem de membros e convites, a consulta
 * do próprio acesso e o link do convite (com um token cifrado de verdade): escopo por empresa, perfis, estados,
 * cabeçalhos e ausência de segredos. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyMembersIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String TOKEN = "kX9fQ-opaque_token-0123456789abcdefghijklmnopqrs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private CompanyInvitationRepository invitationRepository;

    @Autowired
    private InvitationTokenCipher tokenCipher;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyA;
    private UUID companyB;
    private UserEntity owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM companies");
        companyA = insertCompany("11111111000111");
        companyB = insertCompany("22222222000122");
        owner = insertUser(companyA, "João Rangel");
        insertMembership(owner, companyA, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        ownerToken = jwtTokenUtils.generateToken(owner);
    }

    @Test
    void list_ownerSeesActiveMembersAndPendingInvitationsOfItsCompanyOnly() throws Exception {
        // Arrange
        UserEntity ana = insertUser(companyA, "Ana Operadora");
        insertMembership(ana, companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        UserEntity removed = insertUser(companyA, "Bruno Removido");
        insertMembership(removed, companyA, CompanyRole.VIEWER, MembershipStatus.REMOVED);
        insertInvitation(companyA, "Carla Convidada", InvitationStatus.PENDING, owner);
        insertInvitation(companyA, "Davi Aceito", InvitationStatus.ACCEPTED, owner);
        insertInvitation(companyA, "Eva Revogada", InvitationStatus.REVOKED, owner);
        UserEntity stranger = insertUser(companyB, "Fabio de Outra Empresa");
        insertMembership(stranger, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        insertInvitation(companyB, "Gil de Outra Empresa", InvitationStatus.PENDING, stranger);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].kind").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].member.name").value("João Rangel"))
                .andExpect(jsonPath("$.items[0].member.role").value("OWNER"))
                .andExpect(jsonPath("$.items[0].invitation").doesNotExist())
                .andExpect(jsonPath("$.items[1].member.name").value("Ana Operadora"))
                .andExpect(jsonPath("$.items[1].member.email").value("ana.operadora@example.com"))
                .andExpect(jsonPath("$.items[2].kind").value("INVITATION"))
                .andExpect(jsonPath("$.items[2].invitation.name").value("Carla Convidada"))
                .andExpect(jsonPath("$.items[2].invitation.status").value("PENDING"))
                .andExpect(jsonPath("$.items[2].invitation.createdBy.name").value("João Rangel"))
                .andExpect(jsonPath("$.items[2].member").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.summary.activeMembers").value(2))
                .andExpect(jsonPath("$.summary.pendingInvitations").value(1))
                .andExpect(jsonPath("$.summary.peopleCount").value(3));
    }

    @Test
    void list_paginatesAndCountsTheWholeSet() throws Exception {
        // Arrange
        for (String name : new String[]{"Ana", "Bia", "Caio"}) {
            UserEntity member = insertUser(companyA, name);
            insertMembership(member, companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        }
        insertInvitation(companyA, "Duda", InvitationStatus.PENDING, owner);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members?page=1&size=2"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].member.name").value("Bia"))
                .andExpect(jsonPath("$.items[1].member.name").value("Caio"))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.summary.peopleCount").value(5));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members?page=9&size=2"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void list_invalidPageOrSize_returns400WithInvalidParameter() throws Exception {
        for (String query : new String[]{"page=-1", "size=0", "size=101", "page=abc"}) {
            mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members?" + query), ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    @Test
    void list_ownerWithoutOtherPeople_showsOnlyTheOwner() throws Exception {
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.summary.activeMembers").value(1))
                .andExpect(jsonPath("$.summary.pendingInvitations").value(0))
                .andExpect(jsonPath("$.summary.peopleCount").value(1));
    }

    @Test
    void list_onlyTheOwnerOfTheCompanyMayRead() throws Exception {
        // Arrange
        String operator = tokenOf(companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        String viewer = tokenOf(companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        String removedOwner = tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED);
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), operator))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), viewer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), removedOwner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(get("/v1/companies/" + companyA + "/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void administrator_doesNotInheritAMembershipOnTheCompanyMembershipRoutes() throws Exception {
        // Arrange
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdministratorEntity admin = administratorRepository.save(AdministratorEntity.builder()
                .fullName("Admin V-Stable").email("admin@vstable.test").passwordHash("h").passwordSalt("s")
                .createdAt(now).updatedAt(now).build());
        String adminToken = jwtTokenUtils.generateToken(admin);
        CompanyInvitationEntity invitation = insertInvitation(companyA, "Mariana", InvitationStatus.PENDING, owner);
        String invitationUrl = "/v1/companies/" + companyA + "/invitations/" + invitation.getId();

        // Act / Assert
        for (String url : new String[]{"/v1/companies/" + companyA + "/members",
                "/v1/companies/" + companyA + "/members/me", invitationUrl, invitationUrl + "/link"}) {
            mockMvc.perform(authorized(get(url), adminToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"))
                    .andExpect(content().string(not(containsString("inviteUrl"))));
        }
    }

    @Test
    void list_sortsNamesAlphabeticallyForPortugueseIgnoringCaseAndKeepingAccentedLetters() throws Exception {
        // Arrange
        for (String name : new String[]{"Zé Operador", "bruno Operador", "Álvaro Operador"}) {
            UserEntity member = insertUser(companyA, name);
            insertMembership(member, companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        }

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].member.name").value("João Rangel"))
                .andExpect(jsonPath("$.items[1].member.name").value("Álvaro Operador"))
                .andExpect(jsonPath("$.items[2].member.name").value("bruno Operador"))
                .andExpect(jsonPath("$.items[3].member.name").value("Zé Operador"));
    }

    @Test
    void membersMe_everyProfileSeesOnlyItsOwnMembershipIncludingRemovedOnes() throws Exception {
        for (CompanyRole role : CompanyRole.values()) {
            // Arrange: um novo usuário por perfil, ao lado do proprietário.
            UserEntity user = role == CompanyRole.OWNER ? owner : insertUser(companyA, "Usuario " + role);
            if (role != CompanyRole.OWNER) {
                insertMembership(user, companyA, role, MembershipStatus.ACTIVE);
            }

            // Act / Assert
            mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members/me"), jwtTokenUtils.generateToken(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                    .andExpect(jsonPath("$.role").value(role.name()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        }

        UserEntity removed = insertUser(companyA, "Usuario Removido");
        insertMembership(removed, companyA, CompanyRole.OPERATOR, MembershipStatus.REMOVED);
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members/me"), jwtTokenUtils.generateToken(removed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.removedAt").exists());
    }

    @Test
    void membersMe_otherCompanyOrNoMembership_isDenied() throws Exception {
        // Arrange
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        UserEntity withoutMembership = insertUser(companyA, "Sem Vinculo");

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members/me"), ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members/me"),
                        jwtTokenUtils.generateToken(withoutMembership)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
    }

    @Test
    void link_returnsTheSameLinkEveryTimeFromTheEncryptedCopyWithoutChangingTheInvitation() throws Exception {
        // Arrange
        CompanyInvitationEntity invitation = insertInvitation(companyA, "Mariana Costa", InvitationStatus.PENDING, owner);
        String url = "/v1/companies/" + companyA + "/invitations/" + invitation.getId() + "/link";

        // Act / Assert
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(authorized(get(url), ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inviteUrl").value("https://frontend.example/convites/empresa#token=" + TOKEN))
                    .andExpect(jsonPath("$.invitation.id").value(invitation.getId().toString()))
                    .andExpect(jsonPath("$.invitation.status").value("PENDING"))
                    .andExpect(jsonPath("$.invitation.version").value(1))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(content().string(not(containsString(invitation.getTokenHash()))))
                    .andExpect(content().string(not(containsString("tokenHash"))))
                    .andExpect(content().string(not(containsString("tokenCiphertext"))));
        }
        CompanyInvitationEntity unchanged = invitationRepository.findById(invitation.getId()).orElseThrow();
        assertThat(unchanged.getVersion()).isEqualTo(1);
        assertThat(unchanged.getTokenHash()).isEqualTo(invitation.getTokenHash());
        assertThat(unchanged.getTokenCiphertext()).isEqualTo(invitation.getTokenCiphertext());
    }

    @Test
    void link_acceptedIs409_revokedIs410_andOthersCompanyOrUnknownIs404() throws Exception {
        // Arrange
        CompanyInvitationEntity accepted = insertInvitation(companyA, "Aceito", InvitationStatus.ACCEPTED, owner);
        CompanyInvitationEntity revoked = insertInvitation(companyA, "Revogado", InvitationStatus.REVOKED, owner);
        UserEntity ownerOfB = insertUser(companyB, "Dono B");
        insertMembership(ownerOfB, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        CompanyInvitationEntity ofB = insertInvitation(companyB, "De B", InvitationStatus.PENDING, ownerOfB);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + accepted.getId() + "/link"), ownerToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + revoked.getId() + "/link"), ownerToken))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_REVOKED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + ofB.getId() + "/link"), ownerToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + UUID.randomUUID() + "/link"), ownerToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    void link_andMetadata_areDeniedToEveryoneButTheOwnerOfTheCompany() throws Exception {
        // Arrange
        CompanyInvitationEntity invitation = insertInvitation(companyA, "Mariana", InvitationStatus.PENDING, owner);
        String link = "/v1/companies/" + companyA + "/invitations/" + invitation.getId() + "/link";
        String metadata = "/v1/companies/" + companyA + "/invitations/" + invitation.getId();

        // Act / Assert
        for (CompanyRole role : new CompanyRole[]{CompanyRole.OPERATOR, CompanyRole.VIEWER}) {
            String token = tokenOf(companyA, role, MembershipStatus.ACTIVE);
            mockMvc.perform(authorized(get(link), token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
            mockMvc.perform(authorized(get(metadata), token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        mockMvc.perform(authorized(get(link), ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(get(metadata), tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
    }

    @Test
    void metadata_showsAnyStatusWithETagAndNeverExposesSecrets() throws Exception {
        // Arrange
        CompanyInvitationEntity pending = insertInvitation(companyA, "Pendente", InvitationStatus.PENDING, owner);
        CompanyInvitationEntity accepted = insertInvitation(companyA, "Aceito", InvitationStatus.ACCEPTED, owner);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + pending.getId()), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.createdBy.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.inviteUrl").doesNotExist())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString(pending.getTokenHash()))))
                .andExpect(content().string(not(containsString("hash"))))
                .andExpect(content().string(not(containsString("cipher"))));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/invitations/" + accepted.getId()), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedAt").exists());
    }

    @Test
    void listOfMembers_neverExposesPasswordsSecretsNorInvitationTokens() throws Exception {
        // Arrange
        CompanyInvitationEntity invitation = insertInvitation(companyA, "Mariana", InvitationStatus.PENDING, owner);

        // Act / Assert
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString(invitation.getTokenHash()))))
                .andExpect(content().string(not(containsString("senha-secreta-hash"))))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("passwordSalt"))))
                .andExpect(content().string(not(containsString("twoFactor"))))
                .andExpect(content().string(not(containsString("inviteUrl"))))
                .andExpect(content().string(not(containsString("ciphertext"))));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String tokenOf(UUID companyId, CompanyRole role, MembershipStatus status) {
        UserEntity user = insertUser(companyId, "Usuario " + UUID.randomUUID());
        insertMembership(user, companyId, role, status);
        return jwtTokenUtils.generateToken(user);
    }

    private UUID insertCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UserEntity insertUser(UUID companyId, String fullName) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String email = fullName.toLowerCase().replace(' ', '.') + "@example.com";
        return userRepository.saveAndFlush(UserEntity.builder()
                .companyId(companyId)
                .fullName(fullName)
                .email(email)
                .passwordHash("senha-secreta-hash")
                .passwordSalt("salt")
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

    private CompanyInvitationEntity insertInvitation(UUID companyId, String name, InvitationStatus status,
                                                     UserEntity creator) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean pending = status == InvitationStatus.PENDING;
        return invitationRepository.saveAndFlush(CompanyInvitationEntity.builder()
                .companyId(companyId)
                .name(name)
                .email(UUID.randomUUID() + "@example.com")
                .role(CompanyRole.OPERATOR)
                .status(status)
                .tokenHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
                .tokenCiphertext(pending ? tokenCipher.encrypt(TOKEN) : null)
                .tokenKeyVersion(pending ? tokenCipher.keyVersion() : null)
                .createdBy(creator.getId())
                .createdAt(now)
                .updatedAt(now)
                .acceptedAt(status == InvitationStatus.ACCEPTED ? now : null)
                .revokedAt(status == InvitationStatus.REVOKED ? now : null)
                .build());
    }
}
