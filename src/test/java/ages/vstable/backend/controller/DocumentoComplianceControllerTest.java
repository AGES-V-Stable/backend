package ages.vstable.backend.controller;

import ages.vstable.backend.dto.documento.DocumentoComplianceResponse;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.exception.ArmazenamentoDocumentoException;
import ages.vstable.backend.exception.DocumentoInvalidoException;
import ages.vstable.backend.exception.EmpresaNaoEncontradaException;
import ages.vstable.backend.service.DocumentoComplianceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentoComplianceController.class)
class DocumentoComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentoComplianceService documentoService;

    private UUID empresaId;

    @BeforeEach
    void setUp() {
        empresaId = UUID.randomUUID();
    }

    @Test
    void upload_shouldReturn201WithBody_whenRequestIsValid() throws Exception {
        DocumentoComplianceResponse response = new DocumentoComplianceResponse(
                UUID.randomUUID(),
                empresaId,
                TipoDocumento.CONTRATO_SOCIAL,
                "contrato.pdf",
                "s3://vstable-documentos/empresas/" + empresaId + "/documentos/doc.pdf",
                2048L,
                StatusCompliance.EM_ANALISE,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(documentoService.upload(
                eq(empresaId), eq(TipoDocumento.CONTRATO_SOCIAL), any()))
                .thenReturn(response);

        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "contrato.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "conteudo-pdf".getBytes()))
                        .param("tipoDocumento", "CONTRATO_SOCIAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.empresaId").value(empresaId.toString()))
                .andExpect(jsonPath("$.tipoDocumento").value("CONTRATO_SOCIAL"))
                .andExpect(jsonPath("$.nomeArquivo").value("contrato.pdf"))
                .andExpect(jsonPath("$.urlArquivo").value(response.urlArquivo()))
                .andExpect(jsonPath("$.tamanhoArquivoBytes").value(2048))
                .andExpect(jsonPath("$.status").value("EM_ANALISE"));
    }

    @Test
    void upload_shouldReturn400_whenFileIsInvalid() throws Exception {
        when(documentoService.upload(any(), any(), any()))
                .thenThrow(new DocumentoInvalidoException(
                        "Formato não permitido. Envie um arquivo PDF, PNG ou JPEG"));

        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "notas.txt", MediaType.TEXT_PLAIN_VALUE,
                                "texto".getBytes()))
                        .param("tipoDocumento", "OUTROS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Documento inválido"))
                .andExpect(jsonPath("$.detail")
                        .value("Formato não permitido. Envie um arquivo PDF, PNG ou JPEG"));
    }

    @Test
    void upload_shouldReturn404_whenEmpresaDoesNotExist() throws Exception {
        when(documentoService.upload(any(), any(), any()))
                .thenThrow(new EmpresaNaoEncontradaException(empresaId));

        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "contrato.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "conteudo-pdf".getBytes()))
                        .param("tipoDocumento", "CONTRATO_SOCIAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Empresa não encontrada"))
                .andExpect(jsonPath("$.detail").value("Empresa não encontrada: " + empresaId));
    }

    @Test
    void upload_shouldReturn503_whenStorageIsUnavailable() throws Exception {
        when(documentoService.upload(any(), any(), any()))
                .thenThrow(new ArmazenamentoDocumentoException(
                        "Não foi possível salvar o documento no armazenamento", null));

        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "contrato.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "conteudo-pdf".getBytes()))
                        .param("tipoDocumento", "CONTRATO_SOCIAL"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Armazenamento indisponível"));
    }

    @Test
    void upload_shouldReturn400_whenTipoDocumentoParamIsMissing() throws Exception {
        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "contrato.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "conteudo-pdf".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_shouldReturn400_whenTipoDocumentoParamIsInvalid() throws Exception {
        mockMvc.perform(multipart("/api/empresas/{empresaId}/documentos", empresaId)
                        .file(new MockMultipartFile(
                                "arquivo", "contrato.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "conteudo-pdf".getBytes()))
                        .param("tipoDocumento", "NAO_EXISTE"))
                .andExpect(status().isBadRequest());
    }
}
