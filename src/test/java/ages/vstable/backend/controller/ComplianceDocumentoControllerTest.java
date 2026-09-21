package ages.vstable.backend.controller;

import ages.vstable.backend.dto.compliance.DocumentUploadStartResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.ComplianceDocumentoService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceDocumentoController.class)
class ComplianceDocumentoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ComplianceDocumentoService complianceDocumentoService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    private DocumentUploadStartResponse startResponse() {
        DocumentUploadStartResponse response = new DocumentUploadStartResponse();
        response.setId("doc-123");
        response.setUploadUrlFront("https://s3/front");
        response.setUploadUrlBack("https://s3/back");
        return response;
    }

    private String payload(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // POST - inicia upload de documento
    @Test
    void post_progressoValido_retorna200ComCampos() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceDocumentoService.iniciar(eq(progressoId), any())).thenReturn(Optional.of(startResponse()));

        String body = payload(new HashMap<>() {{
            put("documentType", "ID");
            put("doubleSided", true);
        }});

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc-123"))
                .andExpect(jsonPath("$.uploadUrlFront").value("https://s3/front"))
                .andExpect(jsonPath("$.uploadUrlBack").value("https://s3/back"));
    }

    @Test
    void post_documentTypeInvalido_retorna400() throws Exception {
        UUID progressoId = UUID.randomUUID();

        String body = payload(new HashMap<>() {{
            put("documentType", "CONTRATO_SOCIAL");
            put("doubleSided", false);
        }});

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_progressoInexistente_retorna404() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceDocumentoService.iniciar(eq(progressoId), any())).thenReturn(Optional.empty());

        String body = payload(new HashMap<>() {{
            put("documentType", "PASSPORT");
            put("doubleSided", false);
        }});

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_progressoIdMalFormado_retorna404() throws Exception {
        String body = payload(new HashMap<>() {{
            put("documentType", "PASSPORT");
            put("doubleSided", false);
        }});

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/documento", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAvenia_retorna502() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceDocumentoService.iniciar(eq(progressoId), any()))
                .thenThrow(new AveniaIntegrationException("Falha ao comunicar com a Avenia", new RuntimeException()));

        String body = payload(new HashMap<>() {{
            put("documentType", "ID");
            put("doubleSided", true);
        }});

        mockMvc.perform(post("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Falha ao comunicar com a Avenia"));
    }

    // PUT - conclui documento
    @Test
    void put_progressoValido_retorna204() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceDocumentoService.concluir(eq(progressoId), any())).thenReturn(true);

        String body = payload(new HashMap<>() {{
            put("documentoId", "doc-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void put_progressoInexistente_retorna404() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(complianceDocumentoService.concluir(eq(progressoId), any())).thenReturn(false);

        String body = payload(new HashMap<>() {{
            put("documentoId", "doc-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void put_documentoIdAusente_retorna400() throws Exception {
        UUID progressoId = UUID.randomUUID();

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/documento", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_progressoIdMalFormado_retorna404() throws Exception {
        String body = payload(new HashMap<>() {{
            put("documentoId", "doc-123");
        }});

        mockMvc.perform(put("/v1/onboarding/{id}/compliance/documento", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
