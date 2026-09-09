package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.DocumentoComplianceResponse;
import ages.vstable.backend.dto.empresa.EmpresaCreateRequest;
import ages.vstable.backend.dto.empresa.EmpresaResponse;
import ages.vstable.backend.dto.empresa.EmpresaUpdateRequest;
import ages.vstable.backend.dto.empresa.SituacaoCadastralResponse;
import ages.vstable.backend.entity.DocumentoComplianceEntity;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.exception.CadastroInvalidoException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.EmpresaNotFoundException;
import ages.vstable.backend.repository.DocumentoComplianceRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmpresaService {

    private final EmpresaRepository empresaRepository;
    private final DocumentoComplianceRepository documentoComplianceRepository;
    private final EmpresaDadosValidator empresaDadosValidator;

    public List<EmpresaResponse> findAll() {
        return empresaRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<EmpresaResponse> findById(UUID id) {
        return empresaRepository.findById(id)
                .map(this::toResponse);
    }

    public Optional<EmpresaResponse> findByCnpj(String cnpj) {
        return empresaRepository.findByCnpj(cnpj)
                .map(this::toResponse);
    }

    public EmpresaResponse create(EmpresaCreateRequest request) {
        EmpresaDadosNormalizados dados = empresaDadosValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (empresaRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        EmpresaEntity empresa = new EmpresaEntity();

        applyDados(empresa, dados);
        empresa.setNomeFantasia(normalizeOptional(request.getNomeFantasia()));

        empresa.setStatusKyb(StatusCompliance.PENDENTE);
        empresa.setStatusAml(StatusCompliance.PENDENTE);
        empresa.setSaldoDisponivelBrl(BigDecimal.ZERO);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        empresa.setCriadoEm(now);
        empresa.setAtualizadoEm(now);

        return toResponse(saveOrConflict(empresa));
    }

    public EmpresaResponse update(
            UUID id,
            EmpresaUpdateRequest request
    ) {
        EmpresaEntity empresa = empresaRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Empresa not found")
                );

        EmpresaDadosNormalizados dados = empresaDadosValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (!empresa.getCnpj().equals(dados.cnpj())
                && empresaRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        applyDados(empresa, dados);
        empresa.setNomeFantasia(normalizeOptional(request.getNomeFantasia()));
        empresa.setAtualizadoEm(OffsetDateTime.now(ZoneOffset.UTC));

        return toResponse(saveOrConflict(empresa));
    }

    public void deleteById(UUID id) {
        if (!empresaRepository.existsById(id)) {
            throw new IllegalArgumentException("Empresa not found");
        }

        empresaRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return empresaRepository.existsById(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SituacaoCadastralResponse getSituacaoCadastral(UUID id) {
        EmpresaEntity empresa = empresaRepository.findById(id)
                .orElseThrow(() -> new EmpresaNotFoundException(id));

        if (empresa.getStatusKyb() == null || empresa.getStatusAml() == null) {
            throw new CadastroInvalidoException(id);
        }

        List<DocumentoComplianceEntity> documentos = documentoComplianceRepository.findByEmpresaId(id);

        if (documentos.stream().anyMatch(documento -> documento.getStatus() == null)) {
            throw new CadastroInvalidoException(id);
        }

        SituacaoCadastralResponse response = new SituacaoCadastralResponse();
        response.setId(empresa.getId());
        response.setRazaoSocial(empresa.getRazaoSocial());
        response.setNomeFantasia(empresa.getNomeFantasia());
        response.setCnpj(empresa.getCnpj());
        response.setStatusKyb(empresa.getStatusKyb());
        response.setStatusAml(empresa.getStatusAml());
        response.setStatusGeral(computeStatusGeral(empresa.getStatusKyb(), empresa.getStatusAml()));
        response.setDocumentos(documentos.stream().map(this::toDocumentoResponse).toList());

        return response;
    }

    private StatusCompliance computeStatusGeral(StatusCompliance kyb, StatusCompliance aml) {
        if (kyb == StatusCompliance.REJEITADO || aml == StatusCompliance.REJEITADO) {
            return StatusCompliance.REJEITADO;
        }
        if (kyb == StatusCompliance.EM_ANALISE || aml == StatusCompliance.EM_ANALISE) {
            return StatusCompliance.EM_ANALISE;
        }
        if (kyb == StatusCompliance.APROVADO && aml == StatusCompliance.APROVADO) {
            return StatusCompliance.APROVADO;
        }
        return StatusCompliance.PENDENTE;
    }

    private DocumentoComplianceResponse toDocumentoResponse(DocumentoComplianceEntity entity) {
        DocumentoComplianceResponse response = new DocumentoComplianceResponse();
        response.setId(entity.getId());
        response.setTipoDocumento(entity.getTipoDocumento());
        response.setNomeArquivo(entity.getNomeArquivo());
        response.setTamanhoArquivoBytes(entity.getTamanhoArquivoBytes());
        response.setStatus(entity.getStatus());
        response.setEnviadoEm(entity.getEnviadoEm());
        return response;
    }

    private EmpresaResponse toResponse(EmpresaEntity entity) {
        EmpresaResponse response = new EmpresaResponse();

        response.setId(entity.getId());
        response.setRazaoSocial(entity.getRazaoSocial());
        response.setNomeFantasia(entity.getNomeFantasia());
        response.setCnpj(entity.getCnpj());
        response.setPais(entity.getPais());
        response.setCep(entity.getCep());
        response.setCidade(entity.getCidade());
        response.setEstado(entity.getEstado());
        response.setStatusKyb(entity.getStatusKyb());
        response.setStatusAml(entity.getStatusAml());
        response.setSaldoDisponivelBrl(entity.getSaldoDisponivelBrl());
        response.setCriadoEm(entity.getCriadoEm());
        response.setAtualizadoEm(entity.getAtualizadoEm());

        return response;
    }

    private void applyDados(EmpresaEntity empresa, EmpresaDadosNormalizados dados) {
        empresa.setRazaoSocial(dados.razaoSocial());
        empresa.setCnpj(dados.cnpj());
        empresa.setPais(dados.pais());
        empresa.setCep(dados.cep());
        empresa.setCidade(dados.cidade());
        empresa.setEstado(dados.estado());
    }

    private String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private EmpresaEntity saveOrConflict(EmpresaEntity empresa) {
        try {
            return empresaRepository.saveAndFlush(empresa);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("CNPJ já cadastrado");
        }
    }
}
