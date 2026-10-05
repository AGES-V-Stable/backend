package ages.vstable.backend.configuration.security;

import ages.vstable.backend.controller.AuthController;
import ages.vstable.backend.controller.CompanyController;
import ages.vstable.backend.controller.UserController;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.CompanyService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain (JWT filter, route rules, @PreAuthorize, CORS and the
 * JSON 401/403 responses), which the standalone controller tests bypass.
 */
@WebMvcTest(controllers = {CompanyController.class, UserController.class, AuthController.class})
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

    @MockitoBean
    private UserService userService;

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
