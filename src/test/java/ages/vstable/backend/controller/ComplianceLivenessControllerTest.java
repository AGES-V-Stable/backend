package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.service.ComplianceLivenessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
    void post_progressoValido_retorna200ComCampos() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(progressoId)).thenReturn(Optional.of(startResponse()));

        mockMvc.perform(post("/v1/cadastros/{id}/compliance/liveness", progressoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("liveness-123"))
                .andExpect(jsonPath("$.sessionId").value("session-456"))
                .andExpect(jsonPath("$.livenessUrl").value("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc"))
                .andExpect(jsonPath("$.validateLivenessToken").value("token-789"));
    }

    @Test
    void post_progressoInexistente_retorna404() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(progressoId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/cadastros/{id}/compliance/liveness", progressoId))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_progressoIdMalFormado_retorna404() throws Exception {
        mockMvc.perform(post("/v1/cadastros/{id}/compliance/liveness", "nao-e-um-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAvenia_retorna502() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceLivenessService.iniciar(progressoId))
                .thenThrow(new AveniaIntegrationException("Falha ao comunicar com a Avenia", new RuntimeException()));

        mockMvc.perform(post("/v1/cadastros/{id}/compliance/liveness", progressoId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Falha ao comunicar com a Avenia"));
    }

    // PUT - conclui liveness
    @Test
    void put_progressoValido_retorna204() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceLivenessService.concluir(eq(progressoId), any())).thenReturn(true);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/cadastros/{id}/compliance/liveness", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());
    }

    @Test
    void put_progressoInexistente_retorna404() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceLivenessService.concluir(eq(progressoId), any())).thenReturn(false);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/cadastros/{id}/compliance/liveness", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    void put_livenessIdAusente_retorna400() throws Exception {
        UUID progressoId = UUID.randomUUID();

        mockMvc.perform(put("/v1/cadastros/{id}/compliance/liveness", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_progressoIdMalFormado_retorna404() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("livenessId", "liveness-123");
        }});

        mockMvc.perform(put("/v1/cadastros/{id}/compliance/liveness", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }
}
