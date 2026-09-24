package ages.vstable.backend.controller;

import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.OnboardingService;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OnboardingController.class)
class OnboardingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private OnboardingService onboardingService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

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
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Password and confirmation do not match"));
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
}
