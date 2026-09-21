package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.ComplianceKycService;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceKycController.class)
class ComplianceKycControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ComplianceKycService complianceKycService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    private String validPayload() throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("fullName", "Maria da Silva");
            put("dateOfBirth", "1990-05-20");
            put("taxIdNumber", "52998224725");
            put("email", "maria@empresa.com");
            put("phone", "11987654321");
            put("country", "Brasil");
            put("state", "SP");
            put("city", "São Paulo");
            put("zipCode", "90000000");
            put("streetAddress", "Rua Teste, 100");
        }});
    }

    @Test
    void post_kycValidoComDocumentoELivenessConcluidos_retorna200ComProcessId() throws Exception {
        UUID kycId = UUID.randomUUID();
        KycSubmitResponse response = new KycSubmitResponse();
        response.setAveniaProcessId("kyc-process-123");
        when(complianceKycService.finalizar(eq(kycId), any())).thenReturn(Optional.of(response));

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aveniaProcessId").value("kyc-process-123"));
    }

    @Test
    void post_camposObrigatoriosAusentes_retorna400() throws Exception {
        UUID kycId = UUID.randomUUID();

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_kycInexistente_retorna404() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceKycService.finalizar(eq(kycId), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_kycIdMalFormado_retorna404() throws Exception {
        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_documentoOuLivenessAindaNaoConcluidos_retorna422() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceKycService.finalizar(eq(kycId), any()))
                .thenThrow(new UnprocessableEntityException("Verificação de liveness ainda não foi concluída"));

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Verificação de liveness ainda não foi concluída"));
    }

    @Test
    void post_falhaNaAvenia_retorna502() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceKycService.finalizar(eq(kycId), any()))
                .thenThrow(new AveniaIntegrationException("Falha ao comunicar com a Avenia", new RuntimeException()));

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/kyc", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Falha ao comunicar com a Avenia"));
    }
}
