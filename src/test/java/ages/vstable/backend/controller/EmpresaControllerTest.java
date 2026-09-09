package ages.vstable.backend.controller;

import ages.vstable.backend.dto.empresa.SituacaoCadastralResponse;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.exception.CadastroInvalidoException;
import ages.vstable.backend.exception.EmpresaNotFoundException;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.service.EmpresaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class EmpresaControllerTest {

    @Mock
    private EmpresaService empresaService;

    @InjectMocks
    private EmpresaController empresaController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(empresaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSituacaoCadastral_shouldReturnCurrentStatus() throws Exception {
        UUID id = UUID.randomUUID();
        SituacaoCadastralResponse response = new SituacaoCadastralResponse();
        response.setId(id);
        response.setRazaoSocial("Empresa Teste LTDA");
        response.setNomeFantasia("Empresa Teste");
        response.setCnpj("00.000.000/0001-00");
        response.setStatusKyb(StatusCompliance.APROVADO);
        response.setStatusAml(StatusCompliance.EM_ANALISE);
        response.setStatusGeral(StatusCompliance.EM_ANALISE);
        response.setDocumentos(List.of());

        when(empresaService.getSituacaoCadastral(id)).thenReturn(response);

        mockMvc.perform(get("/api/empresas/{id}/situacao-cadastral", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.statusKyb").value("APROVADO"))
                .andExpect(jsonPath("$.statusAml").value("EM_ANALISE"))
                .andExpect(jsonPath("$.statusGeral").value("EM_ANALISE"))
                .andExpect(jsonPath("$.documentos").isArray());
    }

    @Test
    void getSituacaoCadastral_shouldReturnNotFound_whenEmpresaDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(empresaService.getSituacaoCadastral(id)).thenThrow(new EmpresaNotFoundException(id));

        mockMvc.perform(get("/api/empresas/{id}/situacao-cadastral", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Empresa não encontrada: " + id));
    }

    @Test
    void getSituacaoCadastral_shouldReturnBadRequest_whenIdIsInvalid() throws Exception {
        mockMvc.perform(get("/api/empresas/{id}/situacao-cadastral", "id-invalido"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Parâmetro 'id' inválido"));
    }

    @Test
    void getSituacaoCadastral_shouldReturnUnprocessableEntity_whenCadastroIsInvalid() throws Exception {
        UUID id = UUID.randomUUID();
        when(empresaService.getSituacaoCadastral(id)).thenThrow(new CadastroInvalidoException(id));

        mockMvc.perform(get("/api/empresas/{id}/situacao-cadastral", id))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Content"))
                .andExpect(jsonPath("$.message").value("Cadastro da empresa possui situação inválida: " + id));
    }
}
