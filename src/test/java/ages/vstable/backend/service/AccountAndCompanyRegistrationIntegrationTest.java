package ages.vstable.backend.service;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conta e empresa em etapas separadas (backend#64), pela cadeia HTTP real: segurança, migration
 * V12 (users.company_id opcional) e restrições do PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountAndCompanyRegistrationIntegrationTest {

    private static final String CNPJ = "11.222.333/0001-81";

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM company_memberships");
        jdbcTemplate.update("DELETE FROM avenia_kyc_verifications");
        // Usuários sem empresa não são removidos em cascata pela exclusão das empresas.
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM companies");
    }

    @Test
    void accountWithoutCompany_authenticatesButCannotReachCompanyData() throws Exception {
        // Act
        String token = createAccount("joao@example.com");

        // Assert
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("joao@example.com"))
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.companyAccess").value(nullValue()));
        mockMvc.perform(get("/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_id FROM users WHERE email = ?", UUID.class, "joao@example.com")).isNull();
    }

    @Test
    void registeringTheCompanyMakesTheUserItsOwnerWithTheSameToken() throws Exception {
        // Arrange
        String token = createAccount("joao@example.com");

        // Act
        String body = registerCompany(token, CNPJ)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Assert
        String companyId = JsonPath.read(body, "$.companyId");
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"))
                .andExpect(jsonPath("$.companyAccess.status").value("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM avenia_kyc_verifications WHERE id = ?::uuid", String.class,
                (String) JsonPath.read(body, "$.kycVerificationId"))).isEqualTo("PENDING");
    }

    @Test
    void aUserCannotRegisterASecondCompany() throws Exception {
        // Arrange
        String token = createAccount("joao@example.com");
        registerCompany(token, CNPJ).andExpect(status().isCreated());

        // Act / Assert
        registerCompany(token, "45.997.418/0001-53")
                .andExpect(status().isConflict());
        assertThat(count("companies")).isEqualTo(1);
    }

    @Test
    void knowingTheCnpjOfAnExistingCompanyNeverGrantsAccessToIt() throws Exception {
        // Arrange
        String owner = createAccount("dono@example.com");
        registerCompany(owner, CNPJ).andExpect(status().isCreated());
        String outsider = createAccount("outro@example.com");

        // Act / Assert
        registerCompany(outsider, CNPJ).andExpect(status().isConflict());
        mockMvc.perform(get("/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.companyAccess").value(nullValue()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company_memberships m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                Long.class, "outro@example.com")).isZero();
    }

    @Test
    void registeringACompanyRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/onboarding/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(CNPJ)))
                .andExpect(status().isUnauthorized());
        assertThat(count("companies")).isZero();
    }

    @Test
    void anEmailCanOnlyHaveOneAccount() throws Exception {
        createAccount("joao@example.com");

        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("joao@example.com")))
                .andExpect(status().isConflict());
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void theSingleStepOnboardingKeepsWorking() throws Exception {
        mockMvc.perform(post("/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Joao da Silva","email":"joao@example.com","password":"Senha@123",
                                 "confirmPassword":"Senha@123","legalName":"Empresa Exemplo Ltda",
                                 "cnpj":"11.222.333/0001-81","country":"Brasil","zipCode":"90000-000",
                                 "city":"Porto Alegre","state":"RS"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").isString())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    private String createAccount(String email) throws Exception {
        String body = mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private org.springframework.test.web.servlet.ResultActions registerCompany(String token, String cnpj)
            throws Exception {
        return mockMvc.perform(post("/v1/onboarding/company")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyJson(cnpj)));
    }

    private static String accountJson(String email) {
        return """
                {"fullName":"Joao da Silva","email":"%s","password":"Senha@123","confirmPassword":"Senha@123"}
                """.formatted(email);
    }

    private static String companyJson(String cnpj) {
        return """
                {"legalName":"Empresa Exemplo Ltda","cnpj":"%s","country":"Brasil","zipCode":"90000-000",
                 "city":"Porto Alegre","state":"RS"}
                """.formatted(cnpj);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
