package ages.vstable.backend.controller;

import ages.vstable.backend.dto.empresa.CadastroEmpresaResponse;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.CadastroEmpresaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CadastroEmpresaController.class)
class CadastroEmpresaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CadastroEmpresaService cadastroEmpresaService;

    @Test
    void post_payloadValido_retorna201NoContratoSnakeCase() throws Exception {
        UUID progressoId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        OffsetDateTime atualizadoEm = OffsetDateTime.parse("2026-09-04T15:00:00Z");
        when(cadastroEmpresaService.create(eq(progressoId), any())).thenReturn(
                CadastroEmpresaResponse.builder()
                        .empresaId(empresaId)
                        .progressoCadastroId(progressoId)
                        .etapaAtual(3)
                        .proximaEtapa("compliance")
                        .atualizadoEm(atualizadoEm)
                        .build());

        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido(true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empresa_id").value(empresaId.toString()))
                .andExpect(jsonPath("$.progresso_cadastro_id").value(progressoId.toString()))
                .andExpect(jsonPath("$.etapa_atual").value(3))
                .andExpect(jsonPath("$.proxima_etapa").value("compliance"))
                .andExpect(jsonPath("$.atualizado_em").value("2026-09-04T15:00:00Z"))
                .andExpect(jsonPath("$.empresaId").doesNotExist());
    }

    @Test
    void post_semCidade_retorna201() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(cadastroEmpresaService.create(eq(progressoId), any())).thenReturn(
                CadastroEmpresaResponse.builder().etapaAtual(3).build());

        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido(false)))
                .andExpect(status().isCreated());
    }

    @Test
    void post_semCamposObrigatorios_retorna400() throws Exception {
        mockMvc.perform(post("/v1/cadastros/{id}/empresa", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cidade\":\"Porto Alegre\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void post_payloadMalformado_retorna400() throws Exception {
        mockMvc.perform(post("/v1/cadastros/{id}/empresa", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Payload malformado"));
    }

    @Test
    void post_uuidMalformado_retorna404() throws Exception {
        mockMvc.perform(post("/v1/cadastros/invalido/empresa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadValido(true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Progresso de cadastro não encontrado"));
    }

    @Test
    void post_errosDoServico_preservaStatusEFormato() throws Exception {
        UUID progressoId = UUID.randomUUID();
        when(cadastroEmpresaService.create(eq(progressoId), any()))
                .thenThrow(new NotFoundException("Progresso de cadastro não encontrado"))
                .thenThrow(new ConflictException("CNPJ já cadastrado"))
                .thenThrow(new UnprocessableEntityException("CNPJ em formato inválido"))
                .thenThrow(new RuntimeException("falha"));

        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadValido(true)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadValido(true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("CNPJ já cadastrado"));
        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadValido(true)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/v1/cadastros/{id}/empresa", progressoId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadValido(true)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("Erro interno"));
    }

    private String payloadValido(boolean incluirCidade) {
        return """
                {
                  "razao_social": "Empresa Exemplo Ltda",
                  "pais": "Brasil",
                  "cnpj": "11.222.333/0001-81",
                  "cep": "90000-000",
                  %s
                  "estado": "RS"
                }
                """.formatted(incluirCidade ? "\"cidade\": \"Porto Alegre\"," : "");
    }
}
