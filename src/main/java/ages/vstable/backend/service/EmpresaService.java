package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.EmpresaCreateRequest;
import ages.vstable.backend.dto.empresa.EmpresaResponse;
import ages.vstable.backend.dto.empresa.EmpresaUpdateRequest;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.EmpresaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
        empresa.setTradeName(normalizeOptional(request.getNomeFantasia()));

        empresa.setKybStatus(ComplianceStatus.PENDENTE);
        empresa.setAmlStatus(ComplianceStatus.PENDENTE);
        empresa.setAvailableBalanceBrl(BigDecimal.ZERO);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        empresa.setCreatedAt(now);
        empresa.setUpdatedAt(now);

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
        empresa.setTradeName(normalizeOptional(request.getNomeFantasia()));
        empresa.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

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

    private EmpresaResponse toResponse(EmpresaEntity entity) {
        EmpresaResponse response = new EmpresaResponse();

        response.setId(entity.getId());
        response.setRazaoSocial(entity.getLegalName());
        response.setNomeFantasia(entity.getTradeName());
        response.setCnpj(entity.getCnpj());
        response.setPais(entity.getCountry());
        response.setCep(entity.getZipCode());
        response.setCidade(entity.getCity());
        response.setEstado(entity.getState());
        response.setStatusKyb(entity.getKybStatus());
        response.setStatusAml(entity.getAmlStatus());
        response.setSaldoDisponivelBrl(entity.getAvailableBalanceBrl());
        response.setCriadoEm(entity.getCreatedAt());
        response.setAtualizadoEm(entity.getUpdatedAt());

        return response;
    }

    private void applyDados(EmpresaEntity empresa, EmpresaDadosNormalizados dados) {
        empresa.setLegalName(dados.razaoSocial());
        empresa.setCnpj(dados.cnpj());
        empresa.setCountry(dados.pais());
        empresa.setZipCode(dados.cep());
        empresa.setCity(dados.cidade());
        empresa.setState(dados.estado());
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
