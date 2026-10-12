package ages.vstable.backend.configuration.security;

import ages.vstable.backend.controller.AuthController;
import ages.vstable.backend.controller.CompanyController;
import ages.vstable.backend.controller.TransferDetailsController;
import ages.vstable.backend.controller.TransferController;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.controller.UserController;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.CompanyService;
import ages.vstable.backend.service.TransferDetailsService;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain (JWT filter, route rules, @PreAuthorize, CORS and the
 * JSON 401/403 responses), which the standalone controller tests bypass.
 */
@WebMvcTest(controllers = {CompanyController.class, UserController.class, AuthController.class,
        TransferDetailsController.class, TransferController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class, CustomAuthenticationProvider.class,
        BeanManager.class, JwtTokenUtils.class})
class SecurityChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${api.auth.jwt.secret}")
    private String secret;

    @MockitoBean
    private CompanyService companyService;

    @MockitoBean(name = "userService")
    private UserService userService;

    @MockitoBean
    private TransferDetailsService transferDetailsService;

    @MockitoBean
    private TransferService transferService;

    private final UUID companyId = UUID.randomUUID();
    private UserEntity user;
    private AdministratorEntity admin;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Maria").email("maria@empresa.com").build();
        admin = AdministratorEntity.builder().id(UUID.randomUUID())
                .fullName("Admin").email("admin@vstable.com").build();

        when(userService.loadPrincipal(user.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_USER)).thenReturn(user);
        when(userService.loadPrincipal(admin.getEmail(), JwtTokenUtils.ACCOUNT_TYPE_ADMIN)).thenReturn(admin);
        when(companyService.findAll()).thenReturn(List.of());
        when(companyService.findById(companyId)).thenReturn(Optional.empty());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void protectedRoute_withoutToken_returnsJson401() throws Exception {
        mockMvc.perform(get("/v1/companies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void protectedRoute_withMalformedToken_returnsTokenInvalid() throws Exception {
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void protectedRoute_withExpiredToken_returnsTokenExpired() throws Exception {
        String expired = Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512)
                .compact();

        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/00000000-0000-0000-0000-000000000001"})
    void transferHistory_withUserToken_remainsRestrictedToAdmins(String suffix) throws Exception {
        mockMvc.perform(get("/v1/transfers" + suffix)
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transferService);
    }

    @Test
    void transferHistory_withAdminToken_remainsAllowed() throws Exception {
        when(transferService.findTransfers(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/quote"})
    void transferCreation_withoutToken_requiresAuthentication(String suffix) throws Exception {
        mockMvc.perform(post("/v1/transfers" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(transferService);
    }

    @Test
    void transferQuote_withUserToken_passesTheAuthenticatedUserIdToService() throws Exception {
        when(transferService.quote(any(), eq(user.getId()), eq(user.getCompanyId())))
                .thenReturn(new TransferQuoteResponse(null, null, null, null, null));

        mockMvc.perform(post("/v1/transfers/quote")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"amountType\":\"SOURCE\",\"destinationCurrency\":\"USDC\","
                                + "\"paymentMethod\":\"PIX\",\"beneficiaryId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
        verify(transferService).quote(any(), eq(user.getId()), eq(user.getCompanyId()));
    }

    @Test
    void transferCreation_withUserToken_passesTheAuthenticatedUserIdToService() throws Exception {
        when(transferService.create(any(), eq(user.getId()), eq(user.getCompanyId())))
                .thenReturn(new CreateTransferResponse(TransactionStatus.PROCESSING));

        mockMvc.perform(post("/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"amountType\":\"SOURCE\",\"destinationCurrency\":\"USD\","
                                + "\"paymentMethod\":\"PIX\",\"beneficiaryId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        verify(transferService).create(any(), eq(user.getId()), eq(user.getCompanyId()));
    }

    @Test
    void currentUser_withUserToken_returnsCompanyAndRoles() throws Exception {
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    void adminRoute_withUserToken_isForbidden() throws Exception {
        mockMvc.perform(get("/v1/companies").header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoute_withAdminToken_isAllowed() throws Exception {
        mockMvc.perform(get("/v1/companies").header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(admin))))
                .andExpect(status().isOk());
    }

    @Test
    void ownerRoute_withUserOfAnotherCompany_isForbidden() throws Exception {
        mockMvc.perform(get("/v1/companies/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void login_isPublicEvenWithStaleToken_andReturnsAuthorizationHeader() throws Exception {
        user.setPasswordHash(passwordEncoder.encode("Senha@123"));
        when(userService.loadUserByUsername(anyString())).thenReturn(user);

        mockMvc.perform(post("/v1/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, bearer("stale-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@empresa.com\",\"password\":\"Senha@123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")));
    }

    @Test
    void transferDetails_withoutToken_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/transferencias/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void receiptDownload_withoutToken_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/transferencias/{id}/comprovante", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/comprovante"})
    void transferEndpoints_invalidOrExpiredToken_returnUnauthenticated(String suffix) throws Exception {
        String expired = Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512)
                .compact();
        String wrongSignature = Jwts.builder()
                .subject(user.getEmail())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(new byte[64]), Jwts.SIG.HS512)
                .compact();

        for (String token : List.of("not-a-jwt", expired, wrongSignature)) {
            mockMvc.perform(get("/api/transferencias/" + UUID.randomUUID() + suffix)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .accept(suffix.isEmpty() ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PDF))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(transferDetailsService);
    }

    @Test
    void receiptDownload_withUserToken_keepsPrivateNoStoreAndExposesTheFileName() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferDetailsService.getReceipt(eq(transferId), any(UserEntity.class)))
                .thenReturn(new TransferDetailsService.Receipt("comprovante-transferencia.pdf", new byte[] {'%', 'P', 'D', 'F'}));

        mockMvc.perform(get("/api/transferencias/{id}/comprovante", transferId)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenUtils.generateToken(user)))
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("comprovante-transferencia.pdf")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString(HttpHeaders.CONTENT_DISPOSITION)));
    }

    @Test
    void corsPreflight_fromLocalFrontend_exposesAuthorizationHeader() throws Exception {
        mockMvc.perform(options("/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mockMvc.perform(post("/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@x.com\",\"password\":\"x\"}"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString(HttpHeaders.AUTHORIZATION)));
    }
}
