package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.ComplianceSubmissionResponse;
import ages.vstable.backend.entity.DocumentoComplianceEntity;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.DocumentoComplianceRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComplianceService {

    private static final int ETAPA_COMPLIANCE = 3;
    private static final int ETAPA_CONCLUSAO = 4;
    private static final int MAX_DOCUMENTOS = 5;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final DocumentoComplianceRepository documentoRepository;
    private final EmpresaRepository empresaRepository;
    private final ProgressoCadastroRepository progressoRepository;

    @Transactional
    public ComplianceSubmissionResponse submit(UUID progressoId, String tipoDocumento, List<MultipartFile> arquivos) {
        ProgressoCadastroEntity progresso = progressoRepository.findByIdForUpdate(progressoId)
                .orElseThrow(() -> new NotFoundException("Progresso de cadastro não encontrado"));

        if (progresso.getStatusGeral() != StatusOnboarding.RASCUNHO
                || !Integer.valueOf(ETAPA_COMPLIANCE).equals(progresso.getEtapaAtual())
                || progresso.getEmpresaId() == null) {
            throw new ConflictException("Cadastro não está disponível para envio de compliance");
        }

        TipoDocumento tipo;
        try {
            tipo = TipoDocumento.valueOf(tipoDocumento);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new UnprocessableEntityException("Tipo de documento inválido");
        }

        if (arquivos == null || arquivos.isEmpty() || arquivos.size() > MAX_DOCUMENTOS) {
            throw new UnprocessableEntityException("Envie entre 1 e 5 documentos");
        }

        EmpresaEntity empresa = empresaRepository.findById(progresso.getEmpresaId())
                .orElseThrow(() -> new IllegalStateException("Empresa vinculada ao cadastro não encontrada"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<DocumentoComplianceEntity> documentos = new ArrayList<>();

        for (MultipartFile arquivo : arquivos) {
            if (arquivo.isEmpty() || arquivo.getSize() > MAX_FILE_SIZE) {
                throw new UnprocessableEntityException("Cada documento deve possuir conteúdo e no máximo 10 MB");
            }

            byte[] conteudo;
            try {
                conteudo = arquivo.getBytes();
            } catch (IOException ex) {
                throw new IllegalStateException("Não foi possível ler o documento", ex);
            }

            String mimeType = detectMimeType(conteudo);
            if (mimeType == null) {
                throw new UnprocessableEntityException("Documento inválido. Use PDF, JPG ou PNG");
            }

            String nome = sanitizeFilename(arquivo.getOriginalFilename());
            String hash = sha256(conteudo);
            documentos.add(DocumentoComplianceEntity.builder()
                    .empresaId(empresa.getId())
                    .tipoDocumento(tipo)
                    .nomeArquivo(nome)
                    .urlArquivo("database://compliance/" + hash)
                    .tamanhoArquivoBytes(arquivo.getSize())
                    .mimeType(mimeType)
                    .conteudo(conteudo)
                    .hashSha256(hash)
                    .status(StatusCompliance.EM_ANALISE)
                    .enviadoEm(now)
                    .build());
        }

        documentos = documentoRepository.saveAll(documentos);
        empresa.setStatusKyb(StatusCompliance.EM_ANALISE);
        empresa.setStatusAml(StatusCompliance.EM_ANALISE);
        empresa.setAtualizadoEm(now);
        empresaRepository.save(empresa);

        progresso.setEtapaAtual(ETAPA_CONCLUSAO);
        progresso.setStatusGeral(StatusOnboarding.AGUARDANDO_COMPLIANCE);
        progresso.setStatusComplianceFinal(StatusCompliance.EM_ANALISE);
        progresso.setAtualizadoEm(now);
        progressoRepository.save(progresso);

        return ComplianceSubmissionResponse.builder()
                .progressoCadastroId(progresso.getId())
                .empresaId(empresa.getId())
                .documentosIds(documentos.stream().map(DocumentoComplianceEntity::getId).toList())
                .etapaAtual(progresso.getEtapaAtual())
                .statusGeral(progresso.getStatusGeral())
                .statusComplianceFinal(progresso.getStatusComplianceFinal())
                .atualizadoEm(progresso.getAtualizadoEm())
                .build();
    }

    private String sanitizeFilename(String originalFilename) {
        String filename = originalFilename == null ? "documento" : Path.of(originalFilename).getFileName().toString().trim();
        if (filename.isBlank() || filename.length() > 255) {
            throw new UnprocessableEntityException("Nome do documento inválido");
        }
        return filename;
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-') {
            return "application/pdf";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        return null;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo de hash indisponível", ex);
        }
    }
}
