package ages.vstable.backend.service;

import ages.vstable.backend.dto.documento.DocumentoComplianceResponse;
import ages.vstable.backend.entity.DocumentoComplianceEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.exception.ArmazenamentoDocumentoException;
import ages.vstable.backend.exception.EmpresaNaoEncontradaException;
import ages.vstable.backend.repository.DocumentoComplianceRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.storage.DocumentStorage;
import ages.vstable.backend.storage.StoredDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentoComplianceService {

    private final EmpresaRepository empresaRepository;
    private final DocumentoComplianceRepository documentoRepository;
    private final DocumentStorage documentStorage;
    private final DocumentUploadPolicy uploadPolicy;

    public DocumentoComplianceResponse upload(
            UUID empresaId,
            TipoDocumento tipoDocumento,
            MultipartFile file
    ) {
        ensureEmpresaExists(empresaId);
        ValidatedDocument validatedDocument = uploadPolicy.validate(file);

        UUID documentoId = UUID.randomUUID();
        String objectKey = buildObjectKey(
                empresaId,
                documentoId,
                validatedDocument.extension()
        );
        StoredDocument storedDocument = storeDocument(
                objectKey,
                file,
                validatedDocument.contentType()
        );

        try {
            DocumentoComplianceEntity entity = DocumentoComplianceEntity.builder()
                    .id(documentoId)
                    .empresaId(empresaId)
                    .tipoDocumento(tipoDocumento)
                    .nomeArquivo(validatedDocument.fileName())
                    .urlArquivo(storedDocument.location())
                    .tamanhoArquivoBytes(file.getSize())
                    .status(StatusCompliance.EM_ANALISE)
                    .enviadoEm(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            return toResponse(documentoRepository.saveAndFlush(entity));
        } catch (RuntimeException exception) {
            rollbackStoredDocument(storedDocument.objectKey(), exception);
            throw exception;
        }
    }

    private void ensureEmpresaExists(UUID empresaId) {
        if (!empresaRepository.existsById(empresaId)) {
            throw new EmpresaNaoEncontradaException(empresaId);
        }
    }

    private StoredDocument storeDocument(
            String objectKey,
            MultipartFile file,
            String contentType
    ) {
        try (InputStream content = file.getInputStream()) {
            return documentStorage.store(
                    objectKey,
                    content,
                    file.getSize(),
                    contentType
            );
        } catch (IOException exception) {
            throw new ArmazenamentoDocumentoException(
                    "Não foi possível ler o arquivo enviado",
                    exception
            );
        }
    }

    private void rollbackStoredDocument(String objectKey, RuntimeException originalException) {
        try {
            documentStorage.delete(objectKey);
        } catch (RuntimeException rollbackException) {
            originalException.addSuppressed(rollbackException);
            log.error(
                    "Falha ao remover do armazenamento o documento sem metadados: {}",
                    objectKey,
                    rollbackException
            );
        }
    }

    private String buildObjectKey(UUID empresaId, UUID documentoId, String extension) {
        return "empresas/%s/documentos/%s%s".formatted(
                empresaId,
                documentoId,
                extension
        );
    }

    private DocumentoComplianceResponse toResponse(DocumentoComplianceEntity entity) {
        return new DocumentoComplianceResponse(
                entity.getId(),
                entity.getEmpresaId(),
                entity.getTipoDocumento(),
                entity.getNomeArquivo(),
                entity.getUrlArquivo(),
                entity.getTamanhoArquivoBytes(),
                entity.getStatus(),
                entity.getEnviadoEm()
        );
    }
}
