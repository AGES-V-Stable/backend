package ages.vstable.backend.configuration.security;

import ages.vstable.backend.controller.CompanyInvitationAcceptanceController;
import ages.vstable.backend.dto.membership.InvitationAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationAcceptanceMode;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptResponse;
import ages.vstable.backend.dto.membership.InvitationResolveResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.CompanyInvitationAcceptanceService;
import ages.vstable.backend.service.CompanyMembershipService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadeia real de segurança das rotas de consulta e aceite de convite: quais são públicas, o aceite só com
 * sessão completa (nunca com o token do desafio 2FA), administradores recusados, campos de identidade recusados,
 * o limite conjunto de tentativas por IP e a ausência de segredos nas respostas e nos logs.
 */
@WebMvcTest(controllers = CompanyInvitationAcceptanceController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class, CustomAuthenticationProvider.class,
        BeanManager.class, JwtTokenUtils.class})
@ExtendWith(OutputCaptureExtension.class)
class CompanyInvitationAcceptanceSecurityTest {

    private static final String TOKEN = "Tk8_-invite-token-0123456789abcdefghijklmno";
    private static final String PASSWORD = "Senha@Secreta1";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private CompanyInvitationAcceptanceService acceptanceService;

    @MockitoBean
    private CompanyMembershipService companyMembershipService;

    @MockitoBean(name = "userService")
    private UserService userService;

    private UserEntity user;
    private AdministratorEntity admin;
    /** Cada teste usa um IP próprio, porque o limite é compartilhado pelo contexto do Spring. */
    private String clientIp;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).fullName("Mariana")
                .email("mariana@agrosul.com.br").twoFactorEnabled(true).build();
        admin = AdministratorEntity.builder().id(UUID.randomUUID()).fullName("Admin")
                .email("admin@vstable.com").build();
        when(userService.loadPrincipal(user.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        when(userService.loadPrincipal(admin.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_ADMIN)).thenReturn(admin);
        when(companyMembershipService.findCurrentMembership(any())).thenReturn(Optional.empty());
        clientIp = "198.51.100." + IP_SEQUENCE.getAndIncrement();
    }

    @Test
    void resolve_isPublicAndNotCached() throws Exception {
        // Arrange
        when(acceptanceService.resolve(TOKEN)).thenReturn(resolved());

        // Act / Assert
        call(post("/v1/company-invitations/resolve"), tokenBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceMode").value("REGISTER"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().string(not(containsString(TOKEN))));
    }

    @Test
    void acceptNewUser_isPublicReturns201AndIssuesNoJwt() throws Exception {
        // Arrange
        when(acceptanceService.acceptNewUser(any())).thenReturn(
                new InvitationNewUserAcceptResponse(user.getId(), member(), "LOGIN"));

        // Act / Assert
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextAction").value("LOGIN"))
                .andExpect(jsonPath("$.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    void accept_withoutSession_is401AndDoesNotReachTheService() throws Exception {
        // Act / Assert
        call(post("/v1/company-invitations/accept"), acceptBody(""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void accept_with2faChallengeTokenInsteadOfTheSession_is401TokenInvalid() throws Exception {
        // Arrange: senha correta, mas o segundo fator ainda não foi verificado
        String challengeToken = jwtTokenUtils.generateChallengeToken(user);

        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, "Bearer " + challengeToken),
                acceptBody(""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void accept_withInvalidJwt_is401TokenInvalid() throws Exception {
        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, "Bearer forjado"),
                acceptBody(""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void accept_withCompleteSession_returnsTheMembershipWithETagAndNoStore() throws Exception {
        // Arrange
        when(acceptanceService.accept(any(UserEntity.class), any(InvitationAcceptRequest.class))).thenReturn(member());

        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(user)), acceptBody(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        verify(acceptanceService).accept(any(UserEntity.class), any(InvitationAcceptRequest.class));
    }

    @Test
    void accept_withAdministratorSession_is403IdentityMismatch() throws Exception {
        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(admin)), acceptBody(""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_IDENTITY_MISMATCH"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void acceptNewUser_identityOrPermissionOverrideInTheBody_is400AndCreatesNothing() throws Exception {
        // Act / Assert
        for (String extra : new String[]{"\"email\":\"outra@x.com\",", "\"fullName\":\"Outra\",",
                "\"role\":\"OWNER\",", "\"companyId\":\"" + UUID.randomUUID() + "\","}) {
            call(post("/v1/company-invitations/accept-new-user"), newUserBody(extra))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        }
        verify(acceptanceService, never()).acceptNewUser(any());
    }

    @Test
    void accept_identityOverrideInTheBody_is400() throws Exception {
        // Act / Assert
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(user)),
                acceptBody("\"email\":\"outra@x.com\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_PAYLOAD"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void acceptNewUser_missingTermsVersion_is400ValidationError() throws Exception {
        // Act / Assert
        call(post("/v1/company-invitations/accept-new-user"),
                "{\"token\":\"" + TOKEN + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                        + PASSWORD + "\",\"termsAccepted\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void resolve_invalidToken_returnsStableCodeWithoutEchoingTheToken() throws Exception {
        // Arrange
        when(acceptanceService.resolve(anyString()))
                .thenThrow(new NotFoundException("INVITATION_INVALID", "Este link de convite é inválido."));

        // Act / Assert
        call(post("/v1/company-invitations/resolve"), tokenBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"))
                .andExpect(content().string(not(containsString(TOKEN))));
    }

    @Test
    void publicRoutes_thirtyAttemptsPerMinuteAcrossTheThreeRoutes_thenRateLimitedWithRetryAfter() throws Exception {
        // Arrange
        when(acceptanceService.resolve(anyString()))
                .thenThrow(new NotFoundException("INVITATION_INVALID", "Este link de convite é inválido."));
        for (int i = 0; i < 10; i++) {
            call(post("/v1/company-invitations/resolve"), tokenBody()).andExpect(status().isNotFound());
            call(post("/v1/company-invitations/accept-new-user"), "{").andExpect(status().isBadRequest());
            call(post("/v1/company-invitations/accept"), acceptBody("")).andExpect(status().isUnauthorized());
        }

        // Act / Assert: a 31ª tentativa do mesmo IP é recusada em qualquer das rotas, antes da autenticação
        for (String path : new String[]{"/v1/company-invitations/resolve", "/v1/company-invitations/accept-new-user",
                "/v1/company-invitations/accept"}) {
            call(post(path), tokenBody())
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        }
        // outro IP não é afetado
        clientIp = "198.51.100.250";
        call(post("/v1/company-invitations/resolve"), tokenBody()).andExpect(status().isNotFound());
    }

    @Test
    void rateLimited_responseIsReadableByTheFrontendThroughCors() throws Exception {
        // Act / Assert
        mockMvc.perform(options("/v1/company-invitations/accept")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk());
        when(acceptanceService.resolve(anyString())).thenReturn(resolved());
        call(post("/v1/company-invitations/resolve").header(HttpHeaders.ORIGIN, "http://localhost:5173"), tokenBody())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("ETag")));
    }

    @Test
    void requests_neverWriteTheInviteTokenOrThePasswordToTheLogs(CapturedOutput output) throws Exception {
        // Arrange
        when(acceptanceService.acceptNewUser(any()))
                .thenThrow(new NotFoundException("INVITATION_INVALID", "Este link de convite é inválido."));

        // Act
        call(post("/v1/company-invitations/accept-new-user"), newUserBody(""));
        call(post("/v1/company-invitations/accept").header(HttpHeaders.AUTHORIZATION, bearer(user)),
                acceptBody("\"desconhecido\":1,"));

        // Assert
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(PASSWORD);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(clientIp);
                    return servletRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bearer(UserEntity principal) {
        return "Bearer " + jwtTokenUtils.generateToken(principal);
    }

    private String bearer(AdministratorEntity principal) {
        return "Bearer " + jwtTokenUtils.generateToken(principal);
    }

    private static String tokenBody() {
        return "{\"token\":\"" + TOKEN + "\"}";
    }

    private static String newUserBody(String extraFields) {
        return "{" + extraFields + "\"token\":\"" + TOKEN + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}";
    }

    private static String acceptBody(String extraFields) {
        return "{" + extraFields + "\"token\":\"" + TOKEN + "\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}";
    }

    private static InvitationResolveResponse resolved() {
        return new InvitationResolveResponse(UUID.randomUUID(),
                new InvitationResolveResponse.Company(UUID.randomUUID(), "AgroSul"),
                new InvitationResolveResponse.InvitedBy("João"),
                new InvitationResolveResponse.Recipient("Mariana", "mariana@agrosul.com.br"),
                CompanyRole.OPERATOR, InvitationAcceptanceMode.REGISTER,
                new InvitationResolveResponse.Terms("v1", "https://t", "https://p"));
    }

    private MemberResponse member() {
        return new MemberResponse(UUID.randomUUID(), user.getId(), UUID.randomUUID(), "Mariana",
                "mariana@agrosul.com.br", CompanyRole.OPERATOR, MembershipStatus.ACTIVE, 1,
                OffsetDateTime.now(ZoneOffset.UTC), null);
    }
}
