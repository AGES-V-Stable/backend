package ages.vstable.backend.controller;

import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.UsuarioRepository;
import ages.vstable.backend.service.AcessoService;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AcessoController.class)
class AcessoControllerTest {

    private static final String IDEMPOTENCY_KEY = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AcessoService acessoService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UsuarioRepository usuarioRepository;

    private String payloadValido() throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("nomeCompleto", "Joao da Silva");
            put("email", "joao@example.com");
            put("senha", "Senha123!");
            put("confirmarSenha", "Senha123!");
        }});
    }

    private AcessoResponse respostaValida(UUID token) {
        AcessoResponse response = new AcessoResponse();
        response.setToken(token);
        response.setEtapaAtual(2);
        response.setNomeCompleto("Joao da Silva");
        response.setEmail("joao@example.com");
        return response;
    }

    // 1. Payload valido -> 201, token retornado, sem hash_senha exposto
    @Test
    void post_payloadValido_retorna201ComToken() throws Exception {
        UUID token = UUID.randomUUID();
        when(acessoService.create(eq(IDEMPOTENCY_KEY), any())).thenReturn(respostaValida(token));

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(token.toString()))
                .andExpect(jsonPath("$.email").value("joao@example.com"))
                .andExpect(jsonPath("$.hashSenha").doesNotExist())
                .andExpect(jsonPath("$.senha").doesNotExist());
    }

    // 2. Reenvio idempotente -> continua retornando 201 com os mesmos dados (sem duplicar; a nao-duplicacao e' garantida pelo AcessoService)
    @Test
    void post_reenvioComMesmaIdempotencyKey_retorna201() throws Exception {
        UUID token = UUID.randomUUID();
        when(acessoService.create(eq(IDEMPOTENCY_KEY), any())).thenReturn(respostaValida(token));

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(token.toString()));
    }

    // 3. GET com token valido -> 200 com dados salvos
    @Test
    void get_tokenValido_retorna200ComDados() throws Exception {
        UUID token = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        AcessoResponse response = respostaValida(token);
        response.setEmpresaId(empresaId);
        response.setEtapaAtual(3);
        when(acessoService.findById(token)).thenReturn(Optional.of(response));

        mockMvc.perform(get("/v1/cadastros/{id}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token.toString()))
                .andExpect(jsonPath("$.empresaId").value(empresaId.toString()))
                .andExpect(jsonPath("$.etapaAtual").value(3))
                .andExpect(jsonPath("$.nomeCompleto").value("Joao da Silva"))
                .andExpect(jsonPath("$.hashSenha").doesNotExist());
    }

    // 4. E-mail ja cadastrado -> 409
    @Test
    void post_emailJaCadastrado_retorna409() throws Exception {
        when(acessoService.create(eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new ConflictException("E-mail já cadastrado"));

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("E-mail já cadastrado"));
    }

    // 5 e 6. Senha divergente ou fraca -> 422
    @Test
    void post_senhaInvalida_retorna422() throws Exception {
        when(acessoService.create(eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new UnprocessableEntityException("Senha e confirmação não coincidem"));

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Senha e confirmação não coincidem"));
    }

    // 7. nome_completo ou email ausentes -> 400 (bean validation)
    @Test
    void post_semNomeCompletoOuEmail_retorna400() throws Exception {
        String payloadSemCampos = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("senha", "Senha123!");
            put("confirmarSenha", "Senha123!");
        }});

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadSemCampos))
                .andExpect(status().isBadRequest());
    }

    // Header Idempotency-Key ausente -> 400
    @Test
    void post_semHeaderIdempotencyKey_retorna400() throws Exception {
        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isBadRequest());
    }

    // 9. Falha interna inesperada (ex.: timeout no banco) -> 500
    @Test
    void post_falhaInternaInesperada_retorna500() throws Exception {
        when(acessoService.create(eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new RuntimeException("timeout simulado"));

        mockMvc.perform(post("/v1/cadastros/representante/acesso")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido()))
                .andExpect(status().isInternalServerError());
    }

    // 10. GET com token inexistente -> 404
    @Test
    void get_tokenInexistente_retorna404() throws Exception {
        UUID tokenAleatorio = UUID.randomUUID();
        when(acessoService.findById(tokenAleatorio)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/cadastros/{id}", tokenAleatorio))
                .andExpect(status().isNotFound());
    }

    // GET com token mal formado (nao e' um UUID) -> 404
    @Test
    void get_tokenMalFormado_retorna404() throws Exception {
        mockMvc.perform(get("/v1/cadastros/{id}", "nao-e-um-uuid"))
                .andExpect(status().isNotFound());

        verify(acessoService, org.mockito.Mockito.never()).findById(any());
    }
}
