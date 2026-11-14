package ages.vstable.backend.controller;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokenCipher;
import ages.vstable.backend.utils.InvitationTokens;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa de segurança, a criação, a regeneração e a revogação de
 * convites por link: o que é gravado (hash e cópia cifrada, nunca o token), os conflitos, a versão em
 * If-Match, os limites de geração, a corrida entre pedidos, a ausência de expiração e de chamadas aos
 * provedores financeiros. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class CompanyInvitationsIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("transfers.reconciliation.enabled", () -> "false");
    }

    private static final String LINK_PREFIX = "https://frontend.example/convites/empresa#token=";

    @MockitoBean
    private BlindPayGateway blindPayGateway;

    @MockitoBean
    private AveniaClient aveniaClient;

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
    private InvitationTokens invitationTokens;

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

    // ---- criação ----

    @Test
    void create_owner_storesAPendingInvitationWithTheHashAndTheEncryptedCopyAndNeverTheToken() throws Exception {
        // Act
        MvcResult result = mockMvc.perform(authorized(post(invitationsUrl(companyA)), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  Mariana Costa ", " Mariana@AgroSul.com.br ", "OPERATOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitation.id").exists())
                .andExpect(jsonPath("$.invitation.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.invitation.name").value("Mariana Costa"))
                .andExpect(jsonPath("$.invitation.email").value("mariana@agrosul.com.br"))
                .andExpect(jsonPath("$.invitation.role").value("OPERATOR"))
                .andExpect(jsonPath("$.invitation.status").value("PENDING"))
                .andExpect(jsonPath("$.invitation.version").value(1))
                .andExpect(jsonPath("$.invitation.createdBy.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.invitation.createdBy.name").value("João Rangel"))
                .andExpect(jsonPath("$.invitation.acceptedAt").doesNotExist())
                .andExpect(jsonPath("$.invitation.revokedAt").doesNotExist())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().string(not(containsString("tokenHash"))))
                .andExpect(content().string(not(containsString("tokenCiphertext"))))
                .andReturn();

        // Assert
        String url = JsonPath.read(result.getResponse().getContentAsString(), "$.inviteUrl");
        String token = tokenOf(url);
        assertThat(url).startsWith(LINK_PREFIX);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM company_invitations");
        assertThat(row.get("token_hash")).isEqualTo(invitationTokens.hash(token));
        assertThat(row.get("token_ciphertext")).isNotNull();
        assertThat(row.get("token_key_version")).isEqualTo("v1");
        assertThat(row.get("created_by")).isEqualTo(owner.getId());
        assertThat(row.get("company_id")).isEqualTo(companyA);
        assertThat(count("SELECT count(*) FROM company_invitations ci WHERE ci::text LIKE '%' || ? || '%'", token))
                .isZero();
        assertThat(tokenCipher.decrypt((String) row.get("token_ciphertext"), "v1")).isEqualTo(token);
    }

    @Test
    void create_neverCreatesAUserAMembershipOrAFinancialResourceAndCallsNoProvider() throws Exception {
        // Arrange
        long users = count("SELECT count(*) FROM users");
        long memberships = count("SELECT count(*) FROM company_memberships");
        long providerAccounts = count("SELECT count(*) FROM provider_accounts");

        // Act
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                .header(HttpHeaders.IF_MATCH, "\"1\""));
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), ownerToken)
                .header(HttpHeaders.IF_MATCH, "\"1\""));

        // Assert
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM company_memberships")).isEqualTo(memberships);
        assertThat(count("SELECT count(*) FROM provider_accounts")).isEqualTo(providerAccounts);
        verifyNoInteractions(blindPayGateway, aveniaClient);
    }

    @Test
    void create_listShowsThePendingInvitationWithoutTheSecretAndTheLinkIsRecoverable() throws Exception {
        // Arrange
        String url = createInvitation("mariana@agrosul.com.br", "VIEWER");
        UUID id = invitationId("mariana@agrosul.com.br");

        // Act / Assert: a resposta da criação pode ter se perdido; a lista e o /link recuperam o convite
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingInvitations").value(1))
                .andExpect(jsonPath("$.items[1].kind").value("INVITATION"))
                .andExpect(jsonPath("$.items[1].invitation.id").value(id.toString()))
                .andExpect(jsonPath("$.items[1].invitation.version").value(1))
                .andExpect(content().string(not(containsString(tokenOf(url)))))
                .andExpect(content().string(not(containsString("inviteUrl"))));
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + id + "/link"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUrl").value(url));
        assertThat(count("SELECT count(*) FROM company_invitations WHERE version = 1")).isEqualTo(1);
    }

    @Test
    void create_conflicts_areIdentifiableAndCreateNothing() throws Exception {
        // Arrange
        UserEntity member = insertUser(companyA, "Ana Operadora");
        insertMembership(member, companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        UserEntity elsewhere = insertUser(companyB, "Bia de Outra Empresa");
        insertMembership(elsewhere, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String adminEmail = "admin-" + UUID.randomUUID() + "@vstable.test";
        insertAdministrator(adminEmail);
        String firstUrl = createInvitation("carla@agrosul.com.br", "OPERATOR");
        UUID firstId = invitationId("carla@agrosul.com.br");

        // Act / Assert
        postInvitation(ownerToken, "Ana", member.getEmail().toUpperCase(), "OPERATOR")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"));
        postInvitation(ownerToken, "Eu mesmo", owner.getEmail(), "VIEWER")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"));
        postInvitation(ownerToken, "Carla", "CARLA@agrosul.com.br", "VIEWER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.details.invitationId").value(firstId.toString()));
        postInvitation(ownerToken, "Bia", elsewhere.getEmail(), "OPERATOR")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_IN_OTHER_COMPANY"))
                .andExpect(content().string(not(containsString(companyB.toString()))))
                .andExpect(content().string(not(containsString("Acme"))));
        postInvitation(ownerToken, "Admin", adminEmail.toUpperCase(), "VIEWER")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_RESERVED"));

        assertThat(count("SELECT count(*) FROM company_invitations")).isEqualTo(1);
        // o convite existente continua com o mesmo link e a mesma versão: nada foi regenerado
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + firstId + "/link"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUrl").value(firstUrl))
                .andExpect(jsonPath("$.invitation.version").value(1));
    }

    @Test
    void create_existingAccountWithoutAnActiveMembership_isInvitedAndTheAccountIsLeftUntouched() throws Exception {
        // Arrange
        UserEntity removed = insertUser(companyA, "Davi Removido");
        insertMembership(removed, companyA, CompanyRole.VIEWER, MembershipStatus.REMOVED);
        UserEntity neverLinked = insertUser(companyB, "Eva Sem Vinculo");
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class,
                removed.getId());

        // Act / Assert
        postInvitation(ownerToken, "Outro Nome Para Davi", removed.getEmail(), "OPERATOR")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.invitation.name").value("Outro Nome Para Davi"));
        postInvitation(ownerToken, "Outro Nome Para Eva", neverLinked.getEmail(), "VIEWER")
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class,
                removed.getId())).isEqualTo("Davi Removido");
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class,
                removed.getId())).isEqualTo(hash);
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ? AND status = 'ACTIVE'",
                removed.getId())).isZero();
    }

    @Test
    void create_invalidBodies_areRefusedWith400AndNothingIsStored() throws Exception {
        // Act / Assert
        postRaw("{\"name\":\"J\",\"email\":\"m@x.com\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"   \",\"email\":\"m@x.com\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"" + "a".repeat(256) + "\",\"email\":\"m@x.com\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"Mari\\u0000ana\",\"email\":\"m@x.com\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"sem-arroba\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"Mariana\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"OWNER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"ADMIN\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"VIEWER\",\"companyId\":\"" + companyB + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"VIEWER\",\"createdBy\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        postRaw("{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"VIEWER\",\"status\":\"ACCEPTED\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        postRaw("não é json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        assertThat(count("SELECT count(*) FROM company_invitations")).isZero();
    }

    @Test
    void create_nameOf255CharactersAndEmailWithPlusAndDots_areAcceptedAsTheyAre() throws Exception {
        // Act / Assert
        postInvitation(ownerToken, "a".repeat(255), "Maria.Souza+vendas@AgroSul.com.br", "OPERATOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitation.email").value("maria.souza+vendas@agrosul.com.br"))
                .andExpect(jsonPath("$.invitation.name").value("a".repeat(255)));
    }

    @Test
    void create_onlyTheOwnerOfTheCompanyMayInvite() throws Exception {
        // Arrange
        String operator = tokenOf(companyA, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        String viewer = tokenOf(companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        String removedOwner = tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED);
        String ownerOfB = tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String admin = adminToken();

        // Act / Assert
        postInvitation(operator, "Mariana", "m@x.com", "VIEWER")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        postInvitation(viewer, "Mariana", "m@x.com", "VIEWER")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        postInvitation(removedOwner, "Mariana", "m@x.com", "VIEWER")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        postInvitation(ownerOfB, "Mariana", "m@x.com", "VIEWER")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        postInvitation(admin, "Mariana", "m@x.com", "VIEWER")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(post(invitationsUrl(companyA)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Mariana", "m@x.com", "VIEWER")))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM company_invitations")).isZero();
    }

    @Test
    void create_twoSimultaneousRequestsForTheSameEmail_leaveOneInvitationAndOneConflict() throws Exception {
        // Act
        List<MvcResult> results = inParallel(2, () -> mockMvc.perform(
                        authorized(post(invitationsUrl(companyA)), ownerToken).contentType(MediaType.APPLICATION_JSON)
                                .content(body("Mariana", "mariana@agrosul.com.br", "OPERATOR")))
                .andReturn());

        // Assert
        List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(201, 409);
        MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat((String) JsonPath.read(conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("INVITATION_ALREADY_EXISTS");
        assertThat((String) JsonPath.read(conflict.getResponse().getContentAsString(), "$.details.invitationId"))
                .isEqualTo(invitationId("mariana@agrosul.com.br").toString());
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(1);
    }

    @Test
    void create_afterARevocation_theSameEmailCanBeInvitedAgain() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID first = invitationId("mariana@agrosul.com.br");
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + first), ownerToken)
                .header(HttpHeaders.IF_MATCH, "\"1\"")).andExpect(status().isNoContent());

        // Act
        postInvitation(ownerToken, "Mariana", "mariana@agrosul.com.br", "VIEWER").andExpect(status().isCreated());

        // Assert
        assertThat(count("SELECT count(*) FROM company_invitations")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(1);
    }

    // ---- regeneração ----

    @Test
    void regenerate_ownerGetsANewLinkAndTheOldOneIsReplacedAtomically() throws Exception {
        // Arrange
        String oldUrl = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(2));
        OffsetDateTime createdAt = createdAt(id);
        String oldHash = invitationTokens.hash(tokenOf(oldUrl));

        // Act
        MvcResult result = mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitation.id").value(id.toString()))
                .andExpect(jsonPath("$.invitation.status").value("PENDING"))
                .andExpect(jsonPath("$.invitation.version").value(2))
                .andExpect(jsonPath("$.invitation.name").value("Mariana Costa"))
                .andExpect(jsonPath("$.invitation.email").value("mariana@agrosul.com.br"))
                .andExpect(jsonPath("$.invitation.role").value("OPERATOR"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn();

        // Assert
        String newUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.inviteUrl");
        assertThat(newUrl).startsWith(LINK_PREFIX).isNotEqualTo(oldUrl);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM company_invitations");
        assertThat(row.get("token_hash")).isEqualTo(invitationTokens.hash(tokenOf(newUrl))).isNotEqualTo(oldHash);
        assertThat(row.get("version")).isEqualTo(2L);
        assertThat(createdAt(id)).isEqualTo(createdAt);
        assertThat(jdbcTemplate.queryForList("SELECT token_hash FROM company_invitation_replaced_tokens", String.class))
                .containsExactly(oldHash);
        assertThat(count("SELECT count(*) FROM company_invitations ci WHERE ci::text LIKE '%' || ? || '%'", tokenOf(newUrl)))
                .isZero();
        assertThat(count("SELECT count(*) FROM company_invitation_replaced_tokens r WHERE r::text LIKE '%' || ? || '%'",
                tokenOf(oldUrl))).isZero();
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + id + "/link"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUrl").value(newUrl))
                .andExpect(jsonPath("$.invitation.version").value(2));
    }

    @Test
    void regenerate_requiresTheVersionOfTheInvitation() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(2));
        String url = invitationsUrl(companyA) + "/" + id + "/regenerate";
        String hash = tokenHash(id);

        // Act / Assert
        mockMvc.perform(authorized(post(url), ownerToken))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("VERSION_REQUIRED"));
        mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"7\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_MISMATCH"));
        mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        assertThat(tokenHash(id)).isEqualTo(hash);
        assertThat(version(id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM company_invitation_replaced_tokens")).isZero();
    }

    @Test
    void regenerate_enforcesTheIntervalOf60SecondsBetweenGenerations() throws Exception {
        // Arrange: recém-criado
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        String url = invitationsUrl(companyA) + "/" + id + "/regenerate";
        String hash = tokenHash(id);

        // Act / Assert: logo após a criação
        MvcResult tooSoon = mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INVITATION_RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();
        assertThat(retryAfter(tooSoon)).isBetween(50L, 60L);
        assertThat(tokenHash(id)).isEqualTo(hash);
        assertThat(version(id)).isEqualTo(1);

        // 61 segundos depois da criação a regeneração é permitida; logo em seguida, não
        backdateGenerations(id, Duration.ofSeconds(61));
        mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());
        MvcResult again = mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        assertThat(retryAfter(again)).isBetween(50L, 60L);
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void regenerate_allowsAtMostFiveGenerationsPerHourCountingTheCreation() throws Exception {
        // Arrange: criado há 50 minutos e regenerado 4 vezes (há 40, 30, 20 e 10 minutos) = 5 gerações
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        setCreatedAt(id, Instant.now().minus(Duration.ofMinutes(50)));
        for (int minutes : new int[]{40, 30, 20, 10}) {
            insertReplacedToken(id, Instant.now().minus(Duration.ofMinutes(minutes)));
        }
        jdbcTemplate.update("UPDATE company_invitations SET version = 5 WHERE id = ?", id);
        String url = invitationsUrl(companyA) + "/" + id + "/regenerate";
        String hash = tokenHash(id);

        // Act / Assert: a criação só sai da janela em 10 minutos
        MvcResult refused = mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"5\""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INVITATION_RATE_LIMITED"))
                .andReturn();
        assertThat(retryAfter(refused)).isBetween(590L, 600L);
        assertThat(tokenHash(id)).isEqualTo(hash);
        assertThat(count("SELECT count(*) FROM company_invitation_replaced_tokens")).isEqualTo(4);

        // Quando a geração mais antiga sai da janela, a regeneração volta a ser permitida
        setCreatedAt(id, Instant.now().minus(Duration.ofMinutes(61)));
        mockMvc.perform(authorized(post(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"5\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitation.version").value(6));
    }

    @Test
    void regenerate_notPendingOtherCompanyAndUnknownInvitations_areRefused() throws Exception {
        // Arrange
        CompanyInvitationEntity accepted = insertInvitation(companyA, "Aceito", InvitationStatus.ACCEPTED, owner);
        CompanyInvitationEntity revoked = insertInvitation(companyA, "Revogado", InvitationStatus.REVOKED, owner);
        UserEntity ownerOfB = insertUser(companyB, "Dono B");
        insertMembership(ownerOfB, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        CompanyInvitationEntity ofB = insertInvitation(companyB, "De B", InvitationStatus.PENDING, ownerOfB);

        // Act / Assert
        for (CompanyInvitationEntity closed : List.of(accepted, revoked)) {
            mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + closed.getId() + "/regenerate"), ownerToken)
                            .header(HttpHeaders.IF_MATCH, "\"1\""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITATION_NOT_REGENERABLE"));
        }
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + ofB.getId() + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + UUID.randomUUID() + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNotFound());
        assertThat(version(ofB.getId())).isEqualTo(1);
    }

    @Test
    void regenerate_onlyTheOwnerOfTheCompanyMayRegenerate() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(2));
        String url = invitationsUrl(companyA) + "/" + id + "/regenerate";
        String hash = tokenHash(id);

        // Act / Assert
        for (CompanyRole role : new CompanyRole[]{CompanyRole.OPERATOR, CompanyRole.VIEWER}) {
            mockMvc.perform(authorized(post(url), tokenOf(companyA, role, MembershipStatus.ACTIVE))
                            .header(HttpHeaders.IF_MATCH, "\"1\""))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }
        mockMvc.perform(authorized(post(url), tokenOf(companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(post(url), tokenOf(companyA, CompanyRole.OWNER, MembershipStatus.REMOVED))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(authorized(post(url), adminToken()).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(post(url).header(HttpHeaders.IF_MATCH, "\"1\"")).andExpect(status().isUnauthorized());
        assertThat(tokenHash(id)).isEqualTo(hash);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void regenerate_twoSimultaneousRequestsWithTheSameVersion_oneWinsAndTheOtherSeesTheNewVersion() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(2));

        // Act
        List<MvcResult> results = inParallel(2, () -> mockMvc.perform(
                        authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                                .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andReturn());

        // Assert
        assertThat(results.stream().map(result -> result.getResponse().getStatus()).sorted().toList())
                .containsExactly(200, 412);
        assertThat(version(id)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM company_invitation_replaced_tokens")).isEqualTo(1);
    }

    @Test
    void regenerate_andLink_haveNoExpirationAfterMoreThan72Hours() throws Exception {
        // Arrange
        String url = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(100));

        // Act / Assert
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + id + "/link"), ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inviteUrl").value(url));
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());
    }

    // ---- revogação ----

    @Test
    void revoke_ownerInvalidatesTheLinkAndEliminatesTheRecoverableCopyKeepingTheHash() throws Exception {
        // Arrange
        String url = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        UserEntity account = insertUserWithEmail(companyB, "Mariana Conta Pessoal", "mariana@agrosul.com.br");
        String hash = tokenHash(id);

        // Act
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent());

        // Assert
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM company_invitations");
        assertThat(row.get("status")).isEqualTo("REVOKED");
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(row.get("token_ciphertext")).isNull();
        assertThat(row.get("token_key_version")).isNull();
        assertThat(row.get("token_hash")).isEqualTo(hash);
        assertThat(row.get("version")).isEqualTo(2L);
        assertThat(userRepository.findById(account.getId())).isPresent();
        assertThat(count("SELECT count(*) FROM company_invitations ci WHERE ci::text LIKE '%' || ? || '%'", tokenOf(url)))
                .isZero();
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + id + "/link"), ownerToken))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_REVOKED"));
        mockMvc.perform(authorized(get(invitationsUrl(companyA) + "/" + id), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").exists())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(authorized(get("/v1/companies/" + companyA + "/members"), ownerToken))
                .andExpect(jsonPath("$.summary.pendingInvitations").value(0));
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_NOT_REGENERABLE"));
    }

    @Test
    void revoke_repeatedAfterTheRevocation_isStill204AndChangesNothing() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        String url = invitationsUrl(companyA) + "/" + id;
        mockMvc.perform(authorized(delete(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent());

        // Act / Assert: a mesma versão antiga, e qualquer outra, continuam 204
        mockMvc.perform(authorized(delete(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent());
        mockMvc.perform(authorized(delete(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"9\""))
                .andExpect(status().isNoContent());
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void revoke_requiresTheVersionAndRefusesAnAcceptedInvitation() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        CompanyInvitationEntity accepted = insertInvitation(companyA, "Aceito", InvitationStatus.ACCEPTED, owner);
        String url = invitationsUrl(companyA) + "/" + id;

        // Act / Assert
        mockMvc.perform(authorized(delete(url), ownerToken))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("VERSION_REQUIRED"));
        mockMvc.perform(authorized(delete(url), ownerToken).header(HttpHeaders.IF_MATCH, "\"4\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_MISMATCH"));
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + accepted.getId()), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(statusOf(accepted.getId())).isEqualTo("ACCEPTED");
    }

    @Test
    void revoke_otherCompanyUnknownAndNonOwner_areRefused() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        UserEntity ownerOfB = insertUser(companyB, "Dono B");
        insertMembership(ownerOfB, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        CompanyInvitationEntity ofB = insertInvitation(companyB, "De B", InvitationStatus.PENDING, ownerOfB);

        // Act / Assert
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + ofB.getId()), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + UUID.randomUUID()), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNotFound());
        for (CompanyRole role : new CompanyRole[]{CompanyRole.OPERATOR, CompanyRole.VIEWER}) {
            mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), tokenOf(companyA, role, MembershipStatus.ACTIVE))
                            .header(HttpHeaders.IF_MATCH, "\"1\""))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), jwtTokenUtils.generateToken(ownerOfB))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), adminToken())
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(delete(invitationsUrl(companyA) + "/" + id).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(statusOf(ofB.getId())).isEqualTo("PENDING");
    }

    @Test
    void revokeAndRegenerate_simultaneously_leaveAConsistentInvitation() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");
        backdateGenerations(id, Duration.ofHours(2));
        List<Callable<MvcResult>> calls = List.of(
                () -> mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")).andReturn(),
                () -> mockMvc.perform(authorized(delete(invitationsUrl(companyA) + "/" + id), ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")).andReturn());

        // Act
        List<MvcResult> results = inParallel(calls);

        // Assert: um dos dois ganha a trava; o outro vê a nova versão (412) ou o convite já revogado (409)
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM company_invitations");
        if ("REVOKED".equals(row.get("status"))) {
            assertThat(row.get("token_ciphertext")).isNull();
        } else {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("token_ciphertext")).isNotNull();
        }
        assertThat(row.get("version")).isEqualTo(2L);
        assertThat(results.stream().filter(result -> result.getResponse().getStatus() < 300)).hasSize(1);
    }

    // ---- CORS ----

    @Test
    void cors_preflightOfTheVersionedRoutesAllowsIfMatchOnlyForTheConfiguredOrigins() throws Exception {
        // Arrange
        String url = invitationsUrl(companyA) + "/" + UUID.randomUUID() + "/regenerate";

        // Act / Assert
        mockMvc.perform(options(url)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,if-match"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsStringIgnoringCase("if-match")));
        mockMvc.perform(options(url)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,if-match"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void cors_responsesExposeETagAndRetryAfterToTheFrontend() throws Exception {
        // Arrange
        createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID id = invitationId("mariana@agrosul.com.br");

        // Act / Assert
        mockMvc.perform(authorized(post(invitationsUrl(companyA) + "/" + id + "/regenerate"), ownerToken)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsStringIgnoringCase("Retry-After")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsStringIgnoringCase("ETag")));
    }

    @Test
    void uniqueIndexViolationOfTheRace_doesNotWriteTheInvitedEmailToTheLogs(CapturedOutput output) {
        // Arrange: o mesmo e-mail pendente duas vezes, como na corrida de dois pedidos simultâneos
        String email = "segredo.da.silva@example.com";
        insertInvitationFor(email);

        // Act / Assert
        assertThatThrownBy(() -> insertInvitationFor(email)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(output.getAll()).doesNotContain(email).doesNotContain("segredo.da.silva");
    }

    @Test
    void openApi_documentsTheInvitationRoutesAndTheVersionHeader() throws Exception {
        // Act / Assert
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/companies/{companyId}/invitations'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/companies/{companyId}/invitations/{invitationId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/v1/companies/{companyId}/invitations/{invitationId}/regenerate'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/companies/{companyId}/invitations/{invitationId}/regenerate'].post.parameters[?(@.name=='If-Match')]").exists())
                .andExpect(content().string(not(containsString("rejectUnknownField"))));
    }

    // ---- apoio ----

    private String invitationsUrl(UUID companyId) {
        return "/v1/companies/" + companyId + "/invitations";
    }

    private String body(String name, String email, String role) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    private ResultActions postInvitation(String token, String name, String email,
                                                                              String role) throws Exception {
        return mockMvc.perform(authorized(post(invitationsUrl(companyA)), token)
                .contentType(MediaType.APPLICATION_JSON).content(body(name, email, role)));
    }

    private ResultActions postRaw(String json) throws Exception {
        return mockMvc.perform(authorized(post(invitationsUrl(companyA)), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Cria o convite como proprietário e devolve o link; o nome é sempre "Mariana Costa". */
    private String createInvitation(String email, String role) throws Exception {
        MvcResult result = postInvitation(ownerToken, "Mariana Costa", email, role)
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.inviteUrl");
    }

    private UUID invitationId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM company_invitations WHERE lower(email) = ? "
                + "ORDER BY created_at DESC LIMIT 1", UUID.class, email.toLowerCase());
    }

    private static String tokenOf(String url) {
        return url.substring(url.indexOf("#token=") + "#token=".length());
    }

    private String tokenHash(UUID invitationId) {
        return jdbcTemplate.queryForObject("SELECT token_hash FROM company_invitations WHERE id = ?", String.class,
                invitationId);
    }

    private long version(UUID invitationId) {
        return jdbcTemplate.queryForObject("SELECT version FROM company_invitations WHERE id = ?", Long.class,
                invitationId);
    }

    private String statusOf(UUID invitationId) {
        return jdbcTemplate.queryForObject("SELECT status FROM company_invitations WHERE id = ?", String.class,
                invitationId);
    }

    private OffsetDateTime createdAt(UUID invitationId) {
        return jdbcTemplate.queryForObject("SELECT created_at FROM company_invitations WHERE id = ?",
                OffsetDateTime.class, invitationId);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private long retryAfter(MvcResult result) {
        return Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    }

    /** Recua a criação do convite e as substituições, como se as gerações tivessem ocorrido há {@code age}. */
    private void backdateGenerations(UUID invitationId, Duration age) {
        setCreatedAt(invitationId, Instant.now().minus(age));
        jdbcTemplate.update("UPDATE company_invitation_replaced_tokens SET replaced_at = ? WHERE invitation_id = ?",
                Timestamp.from(Instant.now().minus(age)), invitationId);
    }

    private void setCreatedAt(UUID invitationId, Instant instant) {
        jdbcTemplate.update("UPDATE company_invitations SET created_at = ? WHERE id = ?", Timestamp.from(instant),
                invitationId);
    }

    private void insertReplacedToken(UUID invitationId, Instant replacedAt) {
        jdbcTemplate.update("INSERT INTO company_invitation_replaced_tokens (invitation_id, token_hash, replaced_at) "
                + "VALUES (?, ?, ?)", invitationId, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""), Timestamp.from(replacedAt));
    }

    private List<MvcResult> inParallel(int times, Callable<MvcResult> call) throws Exception {
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            calls.add(call);
        }
        return inParallel(calls);
    }

    /** Executa os pedidos juntos, liberados ao mesmo tempo, e devolve os resultados na ordem dada. */
    private List<MvcResult> inParallel(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String tokenOf(UUID companyId, CompanyRole role, MembershipStatus status) {
        UserEntity user = insertUser(companyId, "Usuario " + UUID.randomUUID());
        insertMembership(user, companyId, role, status);
        return jwtTokenUtils.generateToken(user);
    }

    private String adminToken() {
        return jwtTokenUtils.generateToken(insertAdministrator("admin-" + UUID.randomUUID() + "@vstable.test"));
    }

    private AdministratorEntity insertAdministrator(String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return administratorRepository.save(AdministratorEntity.builder()
                .fullName("Admin V-Stable").email(email).passwordHash("h").passwordSalt("s")
                .createdAt(now).updatedAt(now).build());
    }

    private UUID insertCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UserEntity insertUser(UUID companyId, String fullName) {
        return insertUserWithEmail(companyId, fullName, fullName.toLowerCase().replace(' ', '.') + "@example.com");
    }

    private UserEntity insertUserWithEmail(UUID companyId, String fullName, String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
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

    /** Convite pendente da empresa A para o e-mail, gravado direto no repositório, sem passar pelas regras. */
    private CompanyInvitationEntity insertInvitationFor(String email) {
        return insertInvitation(companyA, "Convidado", InvitationStatus.PENDING, owner, email);
    }

    private CompanyInvitationEntity insertInvitation(UUID companyId, String name, InvitationStatus status,
                                                     UserEntity creator) {
        return insertInvitation(companyId, name, status, creator, UUID.randomUUID() + "@example.com");
    }

    private CompanyInvitationEntity insertInvitation(UUID companyId, String name, InvitationStatus status,
                                                     UserEntity creator, String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean pending = status == InvitationStatus.PENDING;
        String token = invitationTokens.generate();
        return invitationRepository.saveAndFlush(CompanyInvitationEntity.builder()
                .companyId(companyId)
                .name(name)
                .email(email)
                .role(CompanyRole.OPERATOR)
                .status(status)
                .tokenHash(invitationTokens.hash(token))
                .tokenCiphertext(pending ? tokenCipher.encrypt(token) : null)
                .tokenKeyVersion(pending ? tokenCipher.keyVersion() : null)
                .createdBy(creator.getId())
                .createdAt(now)
                .updatedAt(now)
                .acceptedAt(status == InvitationStatus.ACCEPTED ? now : null)
                .revokedAt(status == InvitationStatus.REVOKED ? now : null)
                .build());
    }
}
