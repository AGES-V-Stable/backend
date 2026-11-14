package ages.vstable.backend.controller;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa (JWT real, filtro que recarrega o vínculo a cada requisição,
 * controller, serviço com bloqueio da linha e handlers de erro), a alteração de perfil e a revogação de membros:
 * efeito imediato sobre tokens já emitidos, proteção do proprietário, controle de versão por If-Match, concorrência
 * e o contrato de erros. Nenhum caminho chama provedores externos. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyMemberManagementIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** O usuário não tem 2FA: um perfil que pode transferir para na regra financeira (422), antes de qualquer provedor. */
    private static final String TRANSFER_BODY = """
            {"amount":100,"amountType":"SOURCE","destinationCurrency":"USD","paymentMethod":"PIX","beneficiaryId":"%s"}
            """.formatted(UUID.randomUUID());

    private static final String TO_VIEWER = "{\"role\":\"VIEWER\"}";
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 30;

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

    @Autowired
    private DataSource dataSource;

    private UUID companyA;
    private UUID companyB;
    private CompanyMembershipEntity ownerMembership;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM companies");
        companyA = insertCompany("11111111000111");
        companyB = insertCompany("22222222000122");
        UserEntity owner = insertUser(companyA, "Owner");
        ownerMembership = insertMembership(owner, companyA, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        ownerToken = jwtTokenUtils.generateToken(owner);
    }

    @Test
    void owner_changesOperatorToViewer_andTheSameTokenLosesTheWritePermissionOnTheNextRequest() throws Exception {
        // Arrange: token do operador emitido antes da mudança e reutilizado depois dela, sem novo login.
        UserEntity operator = insertUser(companyA, "Operador");
        CompanyMembershipEntity membership = insertMembership(operator, companyA, CompanyRole.OPERATOR,
                MembershipStatus.ACTIVE);
        String operatorToken = jwtTokenUtils.generateToken(operator);
        mockMvc.perform(transfer(operatorToken))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));

        // Act
        mockMvc.perform(changeRole(membership.getId(), "\"1\"", TO_VIEWER, ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.id").value(membership.getId().toString()))
                .andExpect(jsonPath("$.userId").value(operator.getId().toString()))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        // Assert: persistido e aplicado ao mesmo JWT na requisição seguinte.
        CompanyMembershipEntity stored = membershipRepository.findById(membership.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(CompanyRole.VIEWER);
        assertThat(stored.getVersion()).isEqualTo(2);
        mockMvc.perform(transfer(operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(authorized(get("/v1/balance"), operatorToken)).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/v1/users/me"), operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyAccess.role").value("VIEWER"))
                .andExpect(jsonPath("$.companyAccess.version").value(2))
                .andExpect(jsonPath("$.companyAccess.permissions", not(hasItem("TRANSFER_CREATE"))))
                .andExpect(jsonPath("$.companyAccess.permissions", not(hasItem("BENEFICIARY_WRITE"))));
    }

    @Test
    void owner_revokesMember_tokenIssuedBeforeLosesCompanyResourcesButPersonalIdentityRemains() throws Exception {
        // Arrange: token emitido antes da revogação.
        UserEntity member = insertUser(companyA, "Membro");
        CompanyMembershipEntity membership = insertMembership(member, companyA, CompanyRole.OPERATOR,
                MembershipStatus.ACTIVE);
        String memberToken = jwtTokenUtils.generateToken(member);
        mockMvc.perform(authorized(get("/v1/balance"), memberToken)).andExpect(status().isOk());

        // Act
        mockMvc.perform(remove(membership.getId(), "\"1\"", ownerToken)).andExpect(status().isNoContent());

        // Assert: o vínculo fica como histórico; a identidade não é apagada.
        CompanyMembershipEntity stored = membershipRepository.findById(membership.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(stored.getRemovedAt()).isNotNull();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(userRepository.findById(member.getId())).isPresent();

        // Assert: o JWT anterior não acessa mais recursos empresariais.
        mockMvc.perform(authorized(get("/v1/balance"), memberToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA), memberToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(get("/api/transferencias"), memberToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(transfer(memberToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        // Assert: a identidade pessoal continua autenticando e enxerga apenas o próprio acesso removido.
        mockMvc.perform(authorized(get("/v1/users/me"), memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.email").value(member.getEmail()))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyAccess.status").value("REMOVED"))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(1))
                .andExpect(jsonPath("$.companyAccess.permissions[0]").value("SELF_MEMBERSHIP_READ"));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members/me"), memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.removedAt").exists());

        // Assert: some da listagem e a repetição do DELETE, mesmo com o ETag antigo, continua 204.
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.activeMembers").value(1));
        mockMvc.perform(remove(membership.getId(), "\"1\"", ownerToken)).andExpect(status().isNoContent());
        assertThat(membershipRepository.findById(membership.getId()).orElseThrow().getVersion()).isEqualTo(2);
    }

    @Test
    void owner_cannotBeRemovedDemotedNorCreatedThroughThePatch() throws Exception {
        // Arrange
        UserEntity operator = insertUser(companyA, "Operador");
        CompanyMembershipEntity operatorMembership = insertMembership(operator, companyA, CompanyRole.OPERATOR,
                MembershipStatus.ACTIVE);

        // Act / Assert: o proprietário não é rebaixado nem removido, nem por ele mesmo.
        mockMvc.perform(changeRole(ownerMembership.getId(), "\"1\"", TO_VIEWER, ownerToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_PROTECTED"));
        mockMvc.perform(remove(ownerMembership.getId(), "\"1\"", ownerToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_PROTECTED"));

        // Act / Assert: OWNER não é um perfil atribuível pela alteração.
        mockMvc.perform(changeRole(operatorMembership.getId(), "\"1\"", "{\"role\":\"OWNER\"}", ownerToken))
                .andExpect(status().isBadRequest());

        // Assert
        CompanyMembershipEntity owner = membershipRepository.findById(ownerMembership.getId()).orElseThrow();
        assertThat(owner.getRole()).isEqualTo(CompanyRole.OWNER);
        assertThat(owner.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(owner.getVersion()).isEqualTo(1);
        CompanyMembershipEntity unchanged = membershipRepository.findById(operatorMembership.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(unchanged.getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentPatchesWithTheSameIfMatch_onlyOneIsAppliedAndTheOtherGets412() throws Exception {
        // Arrange
        UserEntity operator = insertUser(companyA, "Operador");
        CompanyMembershipEntity membership = insertMembership(operator, companyA, CompanyRole.OPERATOR,
                MembershipStatus.ACTIVE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results;
        try {
            // Act: a linha do vínculo fica bloqueada até que as duas requisições estejam esperando por ela,
            // o que garante a disputa sem depender de tempo; então o bloqueio é liberado.
            try (Connection lockHolder = dataSource.getConnection()) {
                lockHolder.setAutoCommit(false);
                try (PreparedStatement lock = lockHolder.prepareStatement(
                        "SELECT id FROM company_memberships WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, membership.getId());
                    lock.executeQuery().close();
                }
                results = List.of(
                        executor.submit(() -> mockMvc.perform(
                                changeRole(membership.getId(), "\"1\"", TO_VIEWER, ownerToken)).andReturn()),
                        executor.submit(() -> mockMvc.perform(
                                changeRole(membership.getId(), "\"1\"", TO_VIEWER, ownerToken)).andReturn()));
                awaitRequestsWaitingForTheMembershipLock(2);
                lockHolder.commit();
            }
            List<Integer> statuses = results.stream().map(CompanyMemberManagementIntegrationTest::statusOf).toList();

            // Assert: exatamente uma escrita; a outra é recusada pela versão já alterada.
            assertThat(statuses).containsExactlyInAnyOrder(200, 412);
            for (Future<MvcResult> result : results) {
                MvcResult mvcResult = result.get();
                if (mvcResult.getResponse().getStatus() == 412) {
                    assertThat(mvcResult.getResponse().getContentAsString()).contains("RESOURCE_VERSION_MISMATCH");
                }
            }
            CompanyMembershipEntity stored = membershipRepository.findById(membership.getId()).orElseThrow();
            assertThat(stored.getRole()).isEqualTo(CompanyRole.VIEWER);
            assertThat(stored.getVersion()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forbidden_403_forNonOwnersOtherCompanyAndRevokedOwner() throws Exception {
        // Arrange
        CompanyMembershipEntity target = insertMembership(insertUser(companyA, "Alvo"), companyA,
                CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        String operator = tokenOf(companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        String viewer = tokenOf(companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String revokedOwner = tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED);

        // Act / Assert
        for (String token : new String[]{operator, viewer}) {
            mockMvc.perform(changeRole(target.getId(), "\"1\"", TO_VIEWER, token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
            mockMvc.perform(remove(target.getId(), "\"1\"", token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }
        mockMvc.perform(changeRole(target.getId(), "\"1\"", TO_VIEWER, ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(remove(target.getId(), "\"1\"", ownerOfB))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(changeRole(target.getId(), "\"1\"", TO_VIEWER, revokedOwner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(remove(target.getId(), "\"1\"", revokedOwner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(patch(membersUrl(target.getId())).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(TO_VIEWER))
                .andExpect(status().isUnauthorized());

        // Assert
        assertUnchanged(target, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
    }

    @Test
    void notFound_404_forUnknownMemberOrMemberOfAnotherCompany() throws Exception {
        // Arrange
        CompanyMembershipEntity memberOfB = insertMembership(insertUser(companyB, "Membro B"), companyB,
                CompanyRole.OPERATOR, MembershipStatus.ACTIVE);

        // Act / Assert
        for (UUID memberId : new UUID[]{UUID.randomUUID(), memberOfB.getId()}) {
            mockMvc.perform(changeRole(memberId, "\"1\"", TO_VIEWER, ownerToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
            mockMvc.perform(remove(memberId, "\"1\"", ownerToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        }

        // Assert
        assertUnchanged(memberOfB, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
    }

    @Test
    void conflict_409_whenChangingTheRoleOfARemovedMember() throws Exception {
        // Arrange
        CompanyMembershipEntity removed = insertMembership(insertUser(companyA, "Removido"), companyA,
                CompanyRole.OPERATOR, MembershipStatus.REMOVED);

        // Act / Assert
        mockMvc.perform(changeRole(removed.getId(), "\"1\"", TO_VIEWER, ownerToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_ACTIVE"));

        // Assert
        assertUnchanged(removed, CompanyRole.OPERATOR, MembershipStatus.REMOVED);
    }

    @Test
    void preconditionFailed_412_whenIfMatchIsStale() throws Exception {
        // Arrange: o vínculo já está na versão 2.
        CompanyMembershipEntity member = insertMembership(insertUser(companyA, "Membro"), companyA,
                CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        mockMvc.perform(changeRole(member.getId(), "\"1\"", TO_VIEWER, ownerToken)).andExpect(status().isOk());

        // Act / Assert
        mockMvc.perform(changeRole(member.getId(), "\"1\"", "{\"role\":\"OPERATOR\"}", ownerToken))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_MISMATCH"));
        mockMvc.perform(remove(member.getId(), "\"1\"", ownerToken))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_MISMATCH"));

        // Assert
        CompanyMembershipEntity stored = membershipRepository.findById(member.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(CompanyRole.VIEWER);
        assertThat(stored.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(stored.getVersion()).isEqualTo(2);
    }

    @Test
    void preconditionRequired_428_whenIfMatchIsMissing() throws Exception {
        // Arrange
        CompanyMembershipEntity member = insertMembership(insertUser(companyA, "Membro"), companyA,
                CompanyRole.OPERATOR, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(authorized(patch(membersUrl(member.getId())), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(TO_VIEWER))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("VERSION_REQUIRED"));
        mockMvc.perform(authorized(delete(membersUrl(member.getId())), ownerToken))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("VERSION_REQUIRED"));

        // Assert
        assertUnchanged(member, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
    }

    private void awaitRequestsWaitingForTheMembershipLock(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (countRequestsWaitingForTheMembershipLock() < expected) {
            if (System.nanoTime() > deadline) {
                fail("As requisições concorrentes não chegaram ao bloqueio do vínculo a tempo.");
            }
            Thread.onSpinWait();
        }
    }

    private int countRequestsWaitingForTheMembershipLock() {
        Integer waiting = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%company_memberships%'
                """, Integer.class);
        return waiting == null ? 0 : waiting;
    }

    private static int statusOf(Future<MvcResult> result) {
        try {
            return result.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponse().getStatus();
        } catch (Exception ex) {
            throw new AssertionError("Requisição concorrente não terminou", ex);
        }
    }

    private void assertUnchanged(CompanyMembershipEntity original, CompanyRole role, MembershipStatus status) {
        CompanyMembershipEntity stored = membershipRepository.findById(original.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(role);
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getVersion()).isEqualTo(original.getVersion());
    }

    private String membersUrl(UUID memberId) {
        return "/v1/companies/" + companyA + "/members/" + memberId;
    }

    private MockHttpServletRequestBuilder changeRole(UUID memberId, String ifMatch, String body, String token) {
        return authorized(patch(membersUrl(memberId)), token)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder remove(UUID memberId, String ifMatch, String token) {
        return authorized(delete(membersUrl(memberId)), token).header(HttpHeaders.IF_MATCH, ifMatch);
    }

    private MockHttpServletRequestBuilder transfer(String token) {
        return authorized(post("/v1/transfers"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(TRANSFER_BODY);
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
        return userRepository.saveAndFlush(UserEntity.builder()
                .companyId(companyId)
                .fullName(fullName)
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
