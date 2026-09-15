package ages.vstable.backend.controller;

import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.UsuarioRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
    private UsuarioRepository usuarioRepository;

    private String payloadValido() throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("nomeCompleto", "Joao da Silva");
            put("email", "joao@example.com");
            put("senha", "Senha@123");
            put("confirmarSenha", "Senha@123");
            put("razaoSocial", "Empresa Exemplo Ltda");
            put("cnpj", "11.222.333/0001-81");
            put("pais", "Brasil");
            put("cep", "90000-000");
            put("cidade", "Porto Alegre");
            put("estado", "RS");
        }});
    }

    @Test
    void post_payloadValido_retorna201ComIds() throws Exception {
        OnboardingResponseDTO response = OnboardingResponseDTO.builder()
                .usuarioId(UUID.randomUUID())
                .empresaId(UUID.randomUUID())
                .verificacaoKycId(UUID.randomUUID())
                .build();
        when(onboardingService.realizarOnboarding(any())).thenReturn(response);

        mockMvc.perform(post("/v1/cadastros/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").value(response.getUsuarioId().toString()))
                .andExpect(jsonPath("$.empresaId").value(response.getEmpresaId().toString()))
                .andExpect(jsonPath("$.verificacaoKycId").value(response.getVerificacaoKycId().toString()))
                .andExpect(jsonPath("$.senha").doesNotExist())
                .andExpect(jsonPath("$.hashSenha").doesNotExist());
    }

    @Test
    void post_semCamposObrigatorios_retorna400() throws Exception {
        String payloadSemCampos = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", "joao@example.com");
        }});

        mockMvc.perform(post("/v1/cadastros/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadSemCampos))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_emailOuCnpjJaCadastrado_retorna409() throws Exception {
        when(onboardingService.realizarOnboarding(any()))
                .thenThrow(new ConflictException("E-mail já cadastrado"));

        mockMvc.perform(post("/v1/cadastros/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isConflict());
    }

    @Test
    void post_senhaEConfirmacaoDivergentes_retorna422() throws Exception {
        when(onboardingService.realizarOnboarding(any()))
                .thenThrow(new UnprocessableEntityException("Senha e confirmação não coincidem"));

        mockMvc.perform(post("/v1/cadastros/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Senha e confirmação não coincidem"));
    }

    @Test
    void post_falhaInternaInesperada_retorna500() throws Exception {
        when(onboardingService.realizarOnboarding(any()))
                .thenThrow(new RuntimeException("timeout simulado"));

        mockMvc.perform(post("/v1/cadastros/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isInternalServerError());
    }
}
