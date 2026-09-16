package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.CompanyRepository;
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
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyDataValidator companyDataValidator;

    public List<CompanyResponse> findAll() {
        return companyRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<CompanyResponse> findById(UUID id) {
        return companyRepository.findById(id)
                .map(this::toResponse);
    }

    public Optional<CompanyResponse> findByCnpj(String cnpj) {
        return companyRepository.findByCnpj(cnpj)
                .map(this::toResponse);
    }

    public CompanyResponse create(CompanyCreateRequest request) {
        CompanyNormalizedData dados = companyDataValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (companyRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        CompanyEntity empresa = new CompanyEntity();

        applyDados(empresa, dados);
        empresa.setTradeName(normalizeOptional(request.getNomeFantasia()));

        empresa.setKybStatus(ComplianceStatus.PENDING);
        empresa.setAmlStatus(ComplianceStatus.PENDING);
        empresa.setAvailableBalanceBrl(BigDecimal.ZERO);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        empresa.setCreatedAt(now);
        empresa.setUpdatedAt(now);

        return toResponse(saveOrConflict(empresa));
    }

    public CompanyResponse update(
            UUID id,
            CompanyUpdateRequest request
    ) {
        CompanyEntity empresa = companyRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Empresa not found")
                );

        CompanyNormalizedData dados = companyDataValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (!empresa.getCnpj().equals(dados.cnpj())
                && companyRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        applyDados(empresa, dados);
        empresa.setTradeName(normalizeOptional(request.getNomeFantasia()));
        empresa.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        return toResponse(saveOrConflict(empresa));
    }

    public void deleteById(UUID id) {
        if (!companyRepository.existsById(id)) {
            throw new IllegalArgumentException("Empresa not found");
        }

        companyRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return companyRepository.existsById(id);
    }

    private CompanyResponse toResponse(CompanyEntity entity) {
        CompanyResponse response = new CompanyResponse();

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

    private void applyDados(CompanyEntity empresa, CompanyNormalizedData dados) {
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

    private CompanyEntity saveOrConflict(CompanyEntity empresa) {
        try {
            return companyRepository.saveAndFlush(empresa);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("CNPJ já cadastrado");
        }
    }
}
