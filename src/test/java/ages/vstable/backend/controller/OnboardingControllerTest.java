package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.OnboardingService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.UUID;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OnboardingController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
@WithAnonymousUser
class OnboardingControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private OnboardingService onboardingService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private String validPayload() throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("fullName", "Joao da Silva");
                put("email", "joao@example.com");
                put("password", "Senha@123");
                put("confirmPassword", "Senha@123");
                put("legalName", "Empresa Exemplo Ltda");
                put("cnpj", "11.222.333/0001-81");
                put("country", "Brasil");
                put("zipCode", "90000-000");
                put("city", "Porto Alegre");
                put("state", "RS");
            }
        });
    }

    @Test
    void post_validPayload_returns201WithIds() throws Exception {
        OnboardingResponseDTO response = OnboardingResponseDTO.builder()
                .userId(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .kycVerificationId(UUID.randomUUID())
                .build();
        when(onboardingService.performOnboarding(any())).thenReturn(response);

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(response.getUserId().toString()))
                .andExpect(jsonPath("$.companyId").value(response.getCompanyId().toString()))
                .andExpect(jsonPath("$.kycVerificationId").value(response.getKycVerificationId().toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void post_missingRequiredFields_returns400() throws Exception {
        String payloadWithoutFields = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("email", "joao@example.com");
            }
        });

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payloadWithoutFields))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_invalidEmail_returns422() throws Exception {
        String payload = validPayload().replace("joao@example.com", "email-invalido");

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"))
                .andExpect(jsonPath("$.message").value("Email has an invalid format"));
    }

    @Test
    void post_emailOrCnpjAlreadyRegistered_returns409() throws Exception {
        when(onboardingService.performOnboarding(any()))
                .thenThrow(new ConflictException("Email already registered"));

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isConflict());
    }

    @Test
    void post_passwordAndConfirmationMismatch_returns422() throws Exception {
        when(onboardingService.performOnboarding(any()))
                .thenThrow(new UnprocessableEntityException("Password and confirmation do not match"));

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Password and confirmation do not match"));
    }

    @Test
    void post_missingCompanyField_reportsTheSameFieldNameAsBefore() throws Exception {
        String payload = validPayload().replace("\"Empresa Exemplo Ltda\"", "\"\"");

        mockMvc.perform(post("/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("legalName: ")));
        verifyNoInteractions(onboardingService);
    }

    // --- Conta e empresa em etapas separadas (backend#64) ---

    @Test
    void postAccount_isPublicAndReturns201WithTheToken() throws Exception {
        OnboardingResponseDTO response = OnboardingResponseDTO.builder()
                .userId(UUID.randomUUID())
                .accessToken("jwt-token")
                .build();
        when(onboardingService.createAccount(any())).thenReturn(response);

        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCOUNT_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(response.getUserId().toString()))
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.companyId").value(nullValue()))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void postAccount_invalidEmail_returns422LikeTheSingleStepOnboarding() throws Exception {
        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCOUNT_PAYLOAD.replace("joao@example.com", "email-invalido")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"))
                .andExpect(jsonPath("$.message").value("Email has an invalid format"));
        verifyNoInteractions(onboardingService);
    }

    @Test
    void postAccount_missingFullName_returns400() throws Exception {
        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCOUNT_PAYLOAD.replace("\"Joao da Silva\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("fullName: ")));
        verifyNoInteractions(onboardingService);
    }

    @Test
    void postAccount_emailAlreadyRegistered_returns409() throws Exception {
        when(onboardingService.createAccount(any())).thenThrow(new ConflictException("Email already registered"));

        mockMvc.perform(post("/v1/onboarding/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCOUNT_PAYLOAD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void postCompany_withoutAuthentication_returns401AndCreatesNothing() throws Exception {
        mockMvc.perform(post("/v1/onboarding/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPANY_PAYLOAD))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(onboardingService);
    }

    @Test
    void postCompany_byAdministrator_returns403AndCreatesNothing() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID()).email("admin@vstable.com").build();

        mockMvc.perform(post("/v1/onboarding/company")
                        .with(authentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPANY_PAYLOAD))
                .andExpect(status().isForbidden());
        verifyNoInteractions(onboardingService);
    }

    @Test
    void postCompany_byAuthenticatedUser_returns201WithTheCompany() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("joao@example.com").build();
        OnboardingResponseDTO response = OnboardingResponseDTO.builder()
                .userId(user.getId())
                .companyId(UUID.randomUUID())
                .kycVerificationId(UUID.randomUUID())
                .build();
        when(onboardingService.registerCompany(eq(user), any())).thenReturn(response);

        mockMvc.perform(post("/v1/onboarding/company")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPANY_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(response.getCompanyId().toString()))
                .andExpect(jsonPath("$.kycVerificationId").value(response.getKycVerificationId().toString()));
    }

    @Test
    void postCompany_missingLegalName_returns400WithTheFieldName() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("joao@example.com").build();

        mockMvc.perform(post("/v1/onboarding/company")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPANY_PAYLOAD.replace("\"Empresa Exemplo Ltda\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("legalName: ")));
        verifyNoInteractions(onboardingService);
    }

    @Test
    void postCompany_userAlreadyInACompany_returns409WithItsCode() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("joao@example.com").build();
        when(onboardingService.registerCompany(eq(user), any())).thenThrow(
                new ConflictException("USER_ALREADY_IN_COMPANY", "User already belongs to a company"));

        mockMvc.perform(post("/v1/onboarding/company")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPANY_PAYLOAD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_IN_COMPANY"));
    }

    private static final String ACCOUNT_PAYLOAD = """
            {"fullName":"Joao da Silva","email":"joao@example.com","password":"Senha@123","confirmPassword":"Senha@123"}
            """;

    private static final String COMPANY_PAYLOAD = """
            {"legalName":"Empresa Exemplo Ltda","cnpj":"11.222.333/0001-81","country":"Brasil",
             "zipCode":"90000-000","city":"Porto Alegre","state":"RS"}
            """;

    private static RequestPostProcessor asUser(UserEntity user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void post_unexpectedInternalFailure_returns500() throws Exception {
        when(onboardingService.performOnboarding(any()))
                .thenThrow(new RuntimeException("timeout simulado"));

        mockMvc.perform(post("/v1/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getMe_byAdministrator_returns403() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID()).email("admin@vstable.com").build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/onboarding/me")
                        .with(authentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(onboardingService);
    }

    @Test
    void getMe_byAuthenticatedCompanyUser_returns200WithCurrentOnboardingStatus() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("joao@example.com").build();
        ages.vstable.backend.dto.onboarding.OnboardingStatusResponse statusResponse =
                ages.vstable.backend.dto.onboarding.OnboardingStatusResponse.builder()
                        .companyId(UUID.randomUUID())
                        .kycVerificationId(UUID.randomUUID())
                        .build();
        when(onboardingService.getCurrentOnboarding(user)).thenReturn(statusResponse);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/onboarding/me")
                        .with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(statusResponse.getCompanyId().toString()))
                .andExpect(jsonPath("$.kycVerificationId").value(statusResponse.getKycVerificationId().toString()));
    }
}
