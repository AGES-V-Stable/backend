package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessStatusResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.ComplianceLivenessService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceLivenessController.class)
class ComplianceLivenessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ComplianceLivenessService complianceLivenessService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    private LivenessStartResponse startResponse() {
        LivenessStartResponse response = new LivenessStartResponse();
        response.setId("liveness-123");
        response.setSessionId("session-456");
        response.setLivenessUrl("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        response.setValidateLivenessToken("token-789");
        return response;
    }

    // POST - inicia liveness
    @Test
    void post_kycValido_retorna200ComCampos() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(kycId)).thenReturn(Optional.of(startResponse()));

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/liveness", kycId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("liveness-123"))
                .andExpect(jsonPath("$.sessionId").value("session-456"))
                .andExpect(jsonPath("$.livenessUrl").value("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc"))
                .andExpect(jsonPath("$.validateLivenessToken").value("token-789"));
    }

    @Test
    void post_kycInexistente_retorna404() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(kycId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/liveness", kycId))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_kycIdMalFormado_retorna404() throws Exception {
        mockMvc.perform(post("/v1/onboarding/{id}/compliance/liveness", "nao-e-um-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAvenia_retorna502() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(kycId))
                .thenThrow(new AveniaIntegrationException("Falha ao comunicar com a Avenia", new RuntimeException()));

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/liveness", kycId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Falha ao comunicar com a Avenia"));
    }

    // GET - consulta status
    @Test
    void get_kycValidoELivenessIdPresente_retorna200ComStatus() throws Exception {
        UUID kycId = UUID.randomUUID();
        LivenessStatusResponse status = new LivenessStatusResponse();
        status.setReady(true);
        status.setStatus("UPLOADED");
        when(complianceLivenessService.consultarStatus(kycId, "liveness-123"))
                .thenReturn(Optional.of(status));

        mockMvc.perform(get("/v1/onboarding/{id}/compliance/liveness/status", kycId)
                        .param("livenessId", "liveness-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void get_semLivenessId_retorna400() throws Exception {
        UUID kycId = UUID.randomUUID();

        mockMvc.perform(get("/v1/onboarding/{id}/compliance/liveness/status", kycId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_kycInexistente_retorna404() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.consultarStatus(kycId, "liveness-123"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/onboarding/{id}/compliance/liveness/status", kycId)
                        .param("livenessId", "liveness-123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_falhaNaAvenia_retorna502() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.consultarStatus(kycId, "liveness-123"))
                .thenThrow(new AveniaIntegrationException("Falha ao comunicar com a Avenia", new RuntimeException()));

        mockMvc.perform(get("/v1/onboarding/{id}/compliance/liveness/status", kycId)
                        .param("livenessId", "liveness-123"))
                .andExpect(status().isBadGateway());
    }

    // PUT - conclui liveness
    @Test
    void put_kycValido_retorna204() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.concluir(eq(kycId), any())).thenReturn(true);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/liveness", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());
    }

    @Test
    void put_kycInexistente_retorna404() throws Exception {
        UUID kycId = UUID.randomUUID();
        when(complianceLivenessService.concluir(eq(kycId), any())).thenReturn(false);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/liveness", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    void put_livenessIdAusente_retorna400() throws Exception {
        UUID kycId = UUID.randomUUID();

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/liveness", kycId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_kycIdMalFormado_retorna404() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/liveness", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }
}
