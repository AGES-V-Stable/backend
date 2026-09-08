package ages.vstable.backend.service;

import ages.vstable.backend.dto.documento.DocumentoComplianceResponse;
import ages.vstable.backend.entity.DocumentoComplianceEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.exception.ArmazenamentoDocumentoException;
import ages.vstable.backend.exception.DocumentoInvalidoException;
import ages.vstable.backend.exception.EmpresaNaoEncontradaException;
import ages.vstable.backend.repository.DocumentoComplianceRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.storage.DocumentStorage;
import ages.vstable.backend.storage.StoredDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentoComplianceServiceTest {

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private DocumentoComplianceRepository documentoRepository;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private DocumentUploadPolicy uploadPolicy;

    @InjectMocks
    private DocumentoComplianceService service;

    private final UUID empresaId = UUID.randomUUID();

    @Test
    void upload_shouldThrowEmpresaNaoEncontrada_whenEmpresaDoesNotExist() {
        MultipartFile file = pdfFile();
        when(empresaRepository.existsById(empresaId)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(empresaId, TipoDocumento.CONTRATO_SOCIAL, file))
                .isInstanceOf(EmpresaNaoEncontradaException.class)
                .hasMessageContaining(empresaId.toString());

        verifyNoInteractions(uploadPolicy, documentStorage, documentoRepository);
    }

    @Test
    void upload_shouldNotStore_whenUploadPolicyRejectsFile() {
        MultipartFile file = pdfFile();
        when(empresaRepository.existsById(empresaId)).thenReturn(true);
        when(uploadPolicy.validate(file))
                .thenThrow(new DocumentoInvalidoException("O arquivo deve ter no máximo 10 MB"));

        assertThatThrownBy(() -> service.upload(empresaId, TipoDocumento.CONTRATO_SOCIAL, file))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("O arquivo deve ter no máximo 10 MB");

        verifyNoInteractions(documentStorage);
        verify(documentoRepository, never()).saveAndFlush(any());
    }

    @Test
    void upload_shouldPersistMetadataAndReturnResponse_whenRequestIsValid() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(empresaRepository.existsById(empresaId)).thenReturn(true);
        when(uploadPolicy.validate(file)).thenReturn(
                new ValidatedDocument("contrato social.pdf", "application/pdf", ".pdf"));
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("pdf-bytes".getBytes()));
        when(file.getSize()).thenReturn(1234L);
        when(documentStorage.store(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> storedAt(invocation.getArgument(0)));
        when(documentoRepository.saveAndFlush(any(DocumentoComplianceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DocumentoComplianceResponse response =
                service.upload(empresaId, TipoDocumento.CONTRATO_SOCIAL, file);

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentStorage).store(objectKeyCaptor.capture(), any(), anyLong(), anyString());
        String objectKey = objectKeyCaptor.getValue();

        ArgumentCaptor<DocumentoComplianceEntity> entityCaptor =
                ArgumentCaptor.forClass(DocumentoComplianceEntity.class);
        verify(documentoRepository).saveAndFlush(entityCaptor.capture());
        DocumentoComplianceEntity persisted = entityCaptor.getValue();

        assertThat(objectKey)
                .isEqualTo("empresas/%s/documentos/%s.pdf".formatted(empresaId, persisted.getId()));

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getEmpresaId()).isEqualTo(empresaId);
        assertThat(persisted.getTipoDocumento()).isEqualTo(TipoDocumento.CONTRATO_SOCIAL);
        assertThat(persisted.getNomeArquivo()).isEqualTo("contrato social.pdf");
        assertThat(persisted.getUrlArquivo()).isEqualTo("s3://vstable-documentos/" + objectKey);
        assertThat(persisted.getTamanhoArquivoBytes()).isEqualTo(1234L);
        assertThat(persisted.getStatus()).isEqualTo(StatusCompliance.EM_ANALISE);
        assertThat(persisted.getEnviadoEm()).isNotNull();

        assertThat(response.id()).isEqualTo(persisted.getId());
        assertThat(response.empresaId()).isEqualTo(empresaId);
        assertThat(response.tipoDocumento()).isEqualTo(TipoDocumento.CONTRATO_SOCIAL);
        assertThat(response.nomeArquivo()).isEqualTo("contrato social.pdf");
        assertThat(response.urlArquivo()).isEqualTo("s3://vstable-documentos/" + objectKey);
        assertThat(response.tamanhoArquivoBytes()).isEqualTo(1234L);
        assertThat(response.status()).isEqualTo(StatusCompliance.EM_ANALISE);
        assertThat(response.enviadoEm()).isEqualTo(persisted.getEnviadoEm());

        verify(documentStorage, never()).delete(any());
    }

    @Test
    void upload_shouldRollBackStoredDocument_whenRepositorySaveFails() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(empresaRepository.existsById(empresaId)).thenReturn(true);
        when(uploadPolicy.validate(file)).thenReturn(
                new ValidatedDocument("contrato.pdf", "application/pdf", ".pdf"));
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("pdf-bytes".getBytes()));
        when(file.getSize()).thenReturn(10L);
        when(documentStorage.store(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> storedAt(invocation.getArgument(0)));
        when(documentoRepository.saveAndFlush(any(DocumentoComplianceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("nome_arquivo viola not-null"));

        assertThatThrownBy(() -> service.upload(empresaId, TipoDocumento.CONTRATO_SOCIAL, file))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("nome_arquivo viola not-null");

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentStorage).store(objectKeyCaptor.capture(), any(), anyLong(), anyString());
        String storedObjectKey = objectKeyCaptor.getValue();

        verify(documentStorage).delete(storedObjectKey);

        InOrder inOrder = inOrder(documentStorage, documentoRepository);
        inOrder.verify(documentStorage).store(anyString(), any(), anyLong(), anyString());
        inOrder.verify(documentoRepository).saveAndFlush(any());
        inOrder.verify(documentStorage).delete(storedObjectKey);
    }

    @Test
    void upload_shouldPropagateOriginalException_whenRollbackDeleteAlsoFails() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(empresaRepository.existsById(empresaId)).thenReturn(true);
        when(uploadPolicy.validate(file)).thenReturn(
                new ValidatedDocument("contrato.pdf", "application/pdf", ".pdf"));
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("pdf-bytes".getBytes()));
        when(file.getSize()).thenReturn(10L);
        when(documentStorage.store(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> storedAt(invocation.getArgument(0)));
        when(documentoRepository.saveAndFlush(any(DocumentoComplianceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("falha ao persistir"));
        doThrow(new ArmazenamentoDocumentoException("S3 fora do ar", null))
                .when(documentStorage).delete(anyString());

        assertThatThrownBy(() -> service.upload(empresaId, TipoDocumento.CONTRATO_SOCIAL, file))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("falha ao persistir")
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .singleElement()
                        .isInstanceOf(ArmazenamentoDocumentoException.class));
    }

    private static StoredDocument storedAt(String objectKey) {
        return new StoredDocument(objectKey, "s3://vstable-documentos/" + objectKey);
    }

    private static MultipartFile pdfFile() {
        return new MockMultipartFile(
                "arquivo", "contrato.pdf", "application/pdf", "pdf-bytes".getBytes());
    }
}
