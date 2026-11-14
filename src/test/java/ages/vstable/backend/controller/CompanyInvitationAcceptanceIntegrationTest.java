package ages.vstable.backend.controller;

import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valida no PostgreSQL real, pela cadeia completa de segurança, a consulta e o aceite do convite por usuário novo
 * e existente: o que é gravado, a preservação da senha e do 2FA, a identidade do destinatário, todos os estados do
 * token, a idempotência e as corridas entre aceites, regenerações e revogações. Ignorado quando não há Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class CompanyInvitationAcceptanceIntegrationTest {

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
    private static final String PASSWORD = "Senha@Forte1";
    /** Segredo TOTP de exemplo (RFC 4648: "Hello!" + 0xDEADBEEF), só para o teste. */
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final byte[] TOTP_KEY = {0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21,
            (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @MockitoBean
    private BlindPayGateway blindPayGateway;

    @MockitoBean
    private AveniaClient aveniaClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyMembershipRepository membershipRepository;

    @Autowired
    private CompanyInvitationRepository invitationRepository;

    @Autowired
    private InvitationTokens invitationTokens;

    @Autowired
    private InvitationTokenCipher tokenCipher;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyA;
    private UUID companyB;
    private UserEntity owner;
    private String ownerToken;
    /** IP próprio de cada teste, para que o limite de tentativas de um não afete o outro. */
    private String clientIp;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM users");
        companyA = insertCompany("11111111000111");
        companyB = insertCompany("22222222000122");
        owner = insertUser(companyA, "João Rangel", "joao@agrosul.com.br", "senha-hash");
        insertMembership(owner, companyA, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        ownerToken = jwtTokenUtils.generateToken(owner);
        clientIp = "10.74." + (IP_SEQUENCE.get() / 250) + "." + (IP_SEQUENCE.getAndIncrement() % 250 + 1);
    }

    // ---- usuário novo ----

    @Test
    void newUser_resolveThenRegister_createsAccountAndMembershipConsumesTheLinkAndLoginWorksNormally()
            throws Exception {
        // Arrange
        String token = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        long companies = count("SELECT count(*) FROM companies");
        long kyc = count("SELECT count(*) FROM avenia_kyc_verifications");

        // Act: consulta sem consumir
        call(post("/v1/company-invitations/resolve"), tokenBody(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.id").value(companyA.toString()))
                .andExpect(jsonPath("$.company.name").value("Acme"))
                .andExpect(jsonPath("$.invitedBy.name").value("João Rangel"))
                .andExpect(jsonPath("$.invitedBy.id").doesNotExist())
                .andExpect(jsonPath("$.recipient.name").value("Mariana Costa"))
                .andExpect(jsonPath("$.recipient.email").value("mariana@agrosul.com.br"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.acceptanceMode").value("REGISTER"))
                .andExpect(jsonPath("$.terms.version").value("v1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING' AND version = 1"))
                .isEqualTo(1);

        // Act: cadastro
        MvcResult created = call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextAction").value("LOGIN"))
                .andExpect(jsonPath("$.membership.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.membership.name").value("Mariana Costa"))
                .andExpect(jsonPath("$.membership.role").value("OPERATOR"))
                .andExpect(jsonPath("$.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.membership.version").value(1))
                .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                .andReturn();

        // Assert: conta, vínculo e convite consumido
        UUID userId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.userId"));
        UserEntity user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("mariana@agrosul.com.br");
        assertThat(user.getFullName()).isEqualTo("Mariana Costa");
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        Map<String, Object> invitation = jdbcTemplate.queryForMap("SELECT * FROM company_invitations");
        assertThat(invitation.get("status")).isEqualTo("ACCEPTED");
        assertThat(invitation.get("accepted_by")).isEqualTo(userId);
        assertThat(invitation.get("membership_id")).isNotNull();
        assertThat(invitation.get("terms_version")).isEqualTo("v1");
        assertThat(invitation.get("terms_accepted_at")).isNotNull();
        assertThat(invitation.get("accepted_at")).isNotNull();
        assertThat(invitation.get("token_ciphertext")).isNull();
        assertThat(invitation.get("version")).isEqualTo(2L);
        // nada de empresa, KYC individual ou provedor financeiro
        assertThat(count("SELECT count(*) FROM companies")).isEqualTo(companies);
        assertThat(count("SELECT count(*) FROM avenia_kyc_verifications")).isEqualTo(kyc);
        verifyNoInteractions(blindPayGateway, aveniaClient);

        // Assert: o acesso começa pelo login normal e o vínculo aparece em /v1/users/me
        MvcResult login = call(post("/v1/auth/login"),
                "{\"email\":\"mariana@agrosul.com.br\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.AUTHORIZATION))
                .andReturn();
        mockMvc.perform(get("/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, login.getResponse().getHeader(HttpHeaders.AUTHORIZATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.companyAccess.role").value("OPERATOR"))
                .andExpect(jsonPath("$.companyAccess.status").value("ACTIVE"));

        // Assert: o link já foi usado
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        call(post("/v1/company-invitations/resolve"), tokenBody(token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        assertThat(count("SELECT count(*) FROM users WHERE email = 'mariana@agrosul.com.br'")).isEqualTo(1);
    }

    @Test
    void newUser_invalidPasswordOrTermsOrExistingAccount_createNothingAndKeepTheInvitationPending() throws Exception {
        // Arrange
        String token = createInvitation("mariana@agrosul.com.br", "VIEWER");
        long users = count("SELECT count(*) FROM users");

        // Act / Assert
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, "fraca"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
        call(post("/v1/company-invitations/accept-new-user"),
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\"Outra@123\","
                        + "\"termsAccepted\":true,\"termsVersion\":\"v1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_CONFIRMATION_MISMATCH"));
        call(post("/v1/company-invitations/accept-new-user"),
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD
                        + "\",\"termsAccepted\":false,\"termsVersion\":\"v1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));
        call(post("/v1/company-invitations/accept-new-user"),
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD
                        + "\",\"termsAccepted\":true,\"termsVersion\":\"v0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_VERSION_MISMATCH"));
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(1);

        // Arrange: a conta passa a existir (com outra caixa no e-mail)
        UserEntity existing = insertUser(null, "Mariana", "Mariana@AgroSul.com.br", passwordEncoder.encode("Antiga@123"));

        // Act / Assert: não troca a senha da conta existente
        call(post("/v1/company-invitations/resolve"), tokenBody(token))
                .andExpect(jsonPath("$.acceptanceMode").value("LOGIN"));
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
        assertThat(userRepository.findById(existing.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(existing.getPasswordHash());
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ?", existing.getId())).isZero();
    }

    // ---- usuário existente ----

    @Test
    void existingUserWith2fa_acceptsOnlyAfterTheFullLoginAndKeepsPasswordAnd2fa() throws Exception {
        // Arrange
        String token = createInvitation("bia@agrosul.com.br", "VIEWER");
        UserEntity bia = userRepository.saveAndFlush(UserEntity.builder()
                .fullName("Bia Nome Atual").email("bia@agrosul.com.br")
                .passwordHash(passwordEncoder.encode(PASSWORD)).passwordSalt("salt")
                .twoFactorEnabled(true).twoFactorSecret(TOTP_SECRET)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT password_hash, two_factor_secret, two_factor_enabled, full_name, email FROM users WHERE id = ?",
                bia.getId());
        call(post("/v1/company-invitations/resolve"), tokenBody(token))
                .andExpect(jsonPath("$.acceptanceMode").value("LOGIN"));

        // Act: a senha sozinha só devolve o desafio, que não autoriza o aceite
        MvcResult challenge = call(post("/v1/auth/login"),
                "{\"email\":\"bia@agrosul.com.br\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires2fa").value(true))
                .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                .andReturn();
        String challengeToken = JsonPath.read(challenge.getResponse().getContentAsString(), "$.challengeToken");
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, "Bearer " + challengeToken),
                acceptBody(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        call(post("/v1/auth/login/verify-2fa"),
                "{\"challengeToken\":\"" + challengeToken + "\",\"code\":\"000000\"}")
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(1);

        // Act: segundo fator correto e aceite explícito
        MvcResult session = call(post("/v1/auth/login/verify-2fa"),
                "{\"challengeToken\":\"" + challengeToken + "\",\"code\":\"" + currentTotp() + "\"}")
                .andExpect(status().isOk())
                .andReturn();
        String jwt = JsonPath.read(session.getResponse().getContentAsString(), "$.token");
        MvcResult accepted = call(post("/v1/company-invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt), acceptBody(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bia.getId().toString()))
                .andExpect(jsonPath("$.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.name").value("Bia Nome Atual"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn();

        // Assert: senha, 2FA e identidade preservados
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT password_hash, two_factor_secret, two_factor_enabled, full_name, email FROM users WHERE id = ?",
                bia.getId());
        assertThat(after.get("password_hash")).isEqualTo(before.get("password_hash"));
        assertThat(after.get("two_factor_enabled")).isEqualTo(true);
        assertThat(after.get("full_name")).isEqualTo(before.get("full_name"));
        assertThat(after.get("email")).isEqualTo(before.get("email"));
        assertThat(userRepository.findById(bia.getId()).orElseThrow().getTwoFactorSecret()).isEqualTo(TOTP_SECRET);
        assertThat(jdbcTemplate.queryForObject("SELECT terms_version FROM company_invitations", String.class))
                .isEqualTo("v1");
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(jsonPath("$.companyAccess.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.companyAccess.role").value("VIEWER"));

        // Act / Assert: repetir o aceite devolve o mesmo vínculo, sem efeito colateral
        String membershipId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.id");
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt),
                acceptBody(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(membershipId));
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ?", bia.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM company_invitations WHERE version = 2")).isEqualTo(1);
        verifyNoInteractions(blindPayGateway, aveniaClient);
    }

    @Test
    void existingUser_otherAccountIsRejectedAndTheRecipientCanStillAcceptAfterSwitchingAccounts() throws Exception {
        // Arrange
        String token = createInvitation("bia@agrosul.com.br", "OPERATOR");
        UserEntity intruder = insertUser(null, "Carla", "carla@agrosul.com.br", "hash");
        UserEntity bia = insertUser(null, "Bia", "bia@agrosul.com.br", "hash");

        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(intruder)),
                acceptBody(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_IDENTITY_MISMATCH"));
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(owner)),
                acceptBody(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_IDENTITY_MISMATCH"));
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ?", intruder.getId())).isZero();
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(1);

        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(bia)), acceptBody(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bia.getId().toString()));
    }

    @Test
    void existingUser_activeInAnotherCompanyOrAlreadyMember_getsConflictWithoutReassociation() throws Exception {
        // Arrange
        UserEntity elsewhere = insertUser(companyB, "Bia", "bia@agrosul.com.br", "hash");
        insertMembership(elsewhere, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String tokenElsewhere = insertPendingInvitation("bia@agrosul.com.br");
        UserEntity member = insertUser(companyA, "Ana", "ana@agrosul.com.br", "hash");
        insertMembership(member, companyA, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        String tokenMember = insertPendingInvitation("ana@agrosul.com.br");

        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(elsewhere)),
                acceptBody(tokenElsewhere))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_IN_OTHER_COMPANY"));
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(member)),
                acceptBody(tokenMember))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"));
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ? AND status = 'ACTIVE'",
                elsewhere.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE id = ?", UUID.class,
                elsewhere.getId())).isEqualTo(companyB);
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'PENDING'")).isEqualTo(2);
    }

    @Test
    void existingUser_repeatAfterRemoval_isAlreadyUsedAndTheLinkNeverReactivatesAccess() throws Exception {
        // Arrange
        String token = createInvitation("bia@agrosul.com.br", "OPERATOR");
        UserEntity bia = insertUser(null, "Bia", "bia@agrosul.com.br", "hash");
        MvcResult accepted = call(post("/v1/company-invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bia)), acceptBody(token))
                .andExpect(status().isOk()).andReturn();
        String memberId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(delete("/v1/companies/" + companyA + "/members/" + memberId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent());

        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(bia)), acceptBody(token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ? AND status = 'ACTIVE'",
                bia.getId())).isZero();
    }

    // ---- estados do token ----

    @Test
    void tokenStates_regeneratedRevokedUnknownAndMalformed_haveExplicitCodesAndCreateNothing() throws Exception {
        // Arrange
        String oldToken = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID invitationId = invitationIdOf("mariana@agrosul.com.br");
        allowRegeneration();
        MvcResult regenerated = mockMvc.perform(post(invitationsUrl() + "/" + invitationId + "/regenerate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk()).andReturn();
        String newToken = tokenOf(JsonPath.read(regenerated.getResponse().getContentAsString(), "$.inviteUrl"));
        long users = count("SELECT count(*) FROM users");

        // Act / Assert: link substituído
        call(post("/v1/company-invitations/resolve"), tokenBody(oldToken))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_SUPERSEDED"));
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(oldToken, PASSWORD))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_SUPERSEDED"));
        call(post("/v1/company-invitations/resolve"), tokenBody(newToken)).andExpect(status().isOk());

        // Act / Assert: revogado
        mockMvc.perform(delete(invitationsUrl() + "/" + invitationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isNoContent());
        call(post("/v1/company-invitations/resolve"), tokenBody(newToken))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_REVOKED"));
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(newToken, PASSWORD))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_REVOKED"));

        // Act / Assert: inexistente e malformado
        call(post("/v1/company-invitations/resolve"), tokenBody(invitationTokens.generate()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        call(post("/v1/company-invitations/resolve"), tokenBody("nao-e-um-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        call(post("/v1/company-invitations/resolve"), "{}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM company_memberships WHERE company_id = ?", companyA)).isEqualTo(1);
    }

    @Test
    void invitationLink_doesNotExpireWithTime() throws Exception {
        // Arrange: convite criado há dois anos
        String token = createInvitation("mariana@agrosul.com.br", "VIEWER");
        jdbcTemplate.update("UPDATE company_invitations SET created_at = created_at - interval '730 days', "
                + "updated_at = updated_at - interval '730 days'");

        // Act / Assert
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD))
                .andExpect(status().isCreated());
    }

    // ---- concorrência ----

    @Test
    void concurrentNewUserAcceptsOfTheSameToken_createExactlyOneAccountAndMembership() throws Exception {
        // Arrange
        String token = createInvitation("mariana@agrosul.com.br", "OPERATOR");

        // Act
        List<MvcResult> results = inParallel(5, () -> call(post("/v1/company-invitations/accept-new-user"),
                newUserBody(token, PASSWORD)).andReturn());

        // Assert
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409)
                .hasSize(4)
                .allSatisfy(r -> assertThat(r.getResponse().getContentAsString()).contains("INVITATION_ALREADY_USED"));
        assertThat(count("SELECT count(*) FROM users WHERE email = 'mariana@agrosul.com.br'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM company_memberships WHERE company_id = ?", companyA)).isEqualTo(2);
    }

    @Test
    void concurrentAcceptsOfTheSameUser_returnTheSameMembershipAndCreateOnlyOne() throws Exception {
        // Arrange
        String token = createInvitation("bia@agrosul.com.br", "VIEWER");
        UserEntity bia = insertUser(null, "Bia", "bia@agrosul.com.br", "hash");
        String jwt = bearer(bia);

        // Act
        List<MvcResult> results = inParallel(5, () -> call(post("/v1/company-invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, jwt), acceptBody(token)).andReturn());

        // Assert
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        assertThat(results.stream().map(this::membershipIdOf).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ?", bia.getId())).isEqualTo(1);
    }

    @Test
    void concurrentAcceptOfTwoCompaniesBySameUser_onlyOneMembershipBecomesActive() throws Exception {
        // Arrange
        UserEntity bia = insertUser(null, "Bia", "bia@agrosul.com.br", "hash");
        String tokenA = insertPendingInvitation(companyA, "bia@agrosul.com.br");
        UserEntity ownerB = insertUser(companyB, "Dono B", "dono@b.com", "hash");
        insertMembership(ownerB, companyB, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        String tokenB = insertPendingInvitation(companyB, "bia@agrosul.com.br", ownerB);
        String jwt = bearer(bia);

        // Act
        List<MvcResult> results = inParallel(List.of(
                () -> call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, jwt),
                        acceptBody(tokenA)).andReturn(),
                () -> call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, jwt),
                        acceptBody(tokenB)).andReturn()));

        // Assert
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409)
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.getResponse().getContentAsString()).contains("USER_IN_OTHER_COMPANY"));
        assertThat(count("SELECT count(*) FROM company_memberships WHERE user_id = ? AND status = 'ACTIVE'",
                bia.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM company_invitations WHERE status = 'ACCEPTED'")).isEqualTo(1);
    }

    @Test
    void acceptRacingRevocation_onlyOneWinsAndTheStateIsConsistent() throws Exception {
        // Arrange
        String token = createInvitation("bia@agrosul.com.br", "OPERATOR");
        UUID invitationId = invitationIdOf("bia@agrosul.com.br");
        UserEntity bia = insertUser(null, "Bia", "bia@agrosul.com.br", "hash");
        String jwt = bearer(bia);

        // Act
        List<MvcResult> results = inParallel(List.of(
                () -> call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, jwt),
                        acceptBody(token)).andReturn(),
                () -> mockMvc.perform(delete(invitationsUrl() + "/" + invitationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")).andReturn()));

        // Assert
        int accept = results.get(0).getResponse().getStatus();
        int revoke = results.get(1).getResponse().getStatus();
        String status = jdbcTemplate.queryForObject("SELECT status FROM company_invitations", String.class);
        long memberships = count("SELECT count(*) FROM company_memberships WHERE user_id = ?", bia.getId());
        if (accept == 200) {
            assertThat(revoke).isEqualTo(409);
            assertThat(status).isEqualTo("ACCEPTED");
            assertThat(memberships).isEqualTo(1);
        } else {
            assertThat(accept).isEqualTo(410);
            assertThat(revoke).isEqualTo(204);
            assertThat(status).isEqualTo("REVOKED");
            assertThat(memberships).isZero();
        }
    }

    @Test
    void newUserAcceptRacingRegeneration_onlyOneWinsAndTheOldLinkNeverWorksAfterwards() throws Exception {
        // Arrange
        String token = createInvitation("mariana@agrosul.com.br", "OPERATOR");
        UUID invitationId = invitationIdOf("mariana@agrosul.com.br");
        allowRegeneration();

        // Act
        List<MvcResult> results = inParallel(List.of(
                () -> call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD)).andReturn(),
                () -> mockMvc.perform(post(invitationsUrl() + "/" + invitationId + "/regenerate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")).andReturn()));

        // Assert
        int accept = results.get(0).getResponse().getStatus();
        int regenerate = results.get(1).getResponse().getStatus();
        long users = count("SELECT count(*) FROM users WHERE email = 'mariana@agrosul.com.br'");
        if (accept == 201) {
            assertThat(regenerate).isEqualTo(409);
            assertThat(users).isEqualTo(1);
        } else {
            assertThat(accept).isEqualTo(410);
            assertThat(regenerate).isEqualTo(200);
            assertThat(users).isZero();
            call(post("/v1/company-invitations/resolve"), tokenBody(token))
                    .andExpect(jsonPath("$.code").value("INVITATION_SUPERSEDED"));
        }
    }

    // ---- segredos ----

    @Test
    void acceptance_neverStoresOrLogsTheTokenOrThePassword(CapturedOutput output) throws Exception {
        // Arrange
        String token = createInvitation("mariana@agrosul.com.br", "OPERATOR");

        // Act
        call(post("/v1/company-invitations/resolve"), tokenBody(token));
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD));
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(token, PASSWORD));

        // Assert
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(PASSWORD);
        assertThat(count("SELECT count(*) FROM company_invitations ci WHERE ci::text LIKE '%' || ? || '%'", token))
                .isZero();
        assertThat(count("SELECT count(*) FROM users u WHERE u::text LIKE '%' || ? || '%'", PASSWORD)).isZero();
    }

    // ---- apoio ----

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(clientIp);
                    return servletRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Cria o convite pela API do proprietário e devolve o token do link. */
    private String createInvitation(String email, String role) throws Exception {
        MvcResult result = mockMvc.perform(post(invitationsUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + (email.startsWith("mariana") ? "Mariana Costa" : "Convidado")
                                + "\",\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return tokenOf(JsonPath.read(result.getResponse().getContentAsString(), "$.inviteUrl"));
    }

    /** Convite pendente gravado direto no banco, sem passar pelas regras de criação. */
    private String insertPendingInvitation(String email) {
        return insertPendingInvitation(companyA, email, owner);
    }

    private String insertPendingInvitation(UUID companyId, String email) {
        return insertPendingInvitation(companyId, email, owner);
    }

    private String insertPendingInvitation(UUID companyId, String email, UserEntity creator) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String token = invitationTokens.generate();
        invitationRepository.saveAndFlush(CompanyInvitationEntity.builder()
                .companyId(companyId)
                .name("Convidado")
                .email(email)
                .role(CompanyRole.OPERATOR)
                .status(InvitationStatus.PENDING)
                .tokenHash(invitationTokens.hash(token))
                .tokenCiphertext(tokenCipher.encrypt(token))
                .tokenKeyVersion(tokenCipher.keyVersion())
                .createdBy(creator.getId())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return token;
    }

    /** Recua a criação dos convites, fora do intervalo mínimo entre gerações de link. */
    private void allowRegeneration() {
        jdbcTemplate.update("UPDATE company_invitations SET created_at = created_at - interval '2 hours'");
    }

    private UUID invitationIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM company_invitations WHERE email = ?", UUID.class, email);
    }

    private String membershipIdOf(MvcResult result) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String invitationsUrl() {
        return "/v1/companies/" + companyA + "/invitations";
    }

    private static String tokenOf(String url) {
        assertThat(url).startsWith(LINK_PREFIX);
        return url.substring(LINK_PREFIX.length());
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private static String newUserBody(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + password
                + "\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}";
    }

    private static String acceptBody(String token) {
        return "{\"token\":\"" + token + "\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}";
    }

    private String bearer(UserEntity user) {
        return "Bearer " + jwtTokenUtils.generateToken(user);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
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

    /** Código TOTP atual do segredo de teste (RFC 6238, HmacSHA1, 6 dígitos, janela de 30 s). */
    private static String currentTotp() throws Exception {
        long window = Math.floorDiv(Instant.now().getEpochSecond(), 30L);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(TOTP_KEY, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(window).array());
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1_000_000);
    }

    private UUID insertCompany(String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Acme', ?)", id, cnpj);
        return id;
    }

    private UserEntity insertUser(UUID companyId, String fullName, String email, String passwordHash) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return userRepository.saveAndFlush(UserEntity.builder()
                .companyId(companyId)
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordHash)
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
}
