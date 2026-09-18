package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
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
        CompanyNormalizedData data = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(), request.getZipCode(),
                request.getCity(), request.getState());

        if (companyRepository.existsByCnpj(data.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        CompanyEntity company = new CompanyEntity();

        applyCompanyData(company, data);
        company.setTradeName(normalizeOptional(request.getTradeName()));

        company.setKybStatus(ComplianceStatus.PENDING);
        company.setAmlStatus(ComplianceStatus.PENDING);
        company.setAvailableBalanceBrl(BigDecimal.ZERO);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        company.setCreatedAt(now);
        company.setUpdatedAt(now);

        return toResponse(saveOrConflict(company));
    }

    public CompanyResponse update(
            UUID id,
            CompanyUpdateRequest request
    ) {
        CompanyEntity company = companyRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException("Company not found")
                );

        CompanyNormalizedData data = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(), request.getZipCode(),
                request.getCity(), request.getState());

        if (!company.getCnpj().equals(data.cnpj())
                && companyRepository.existsByCnpj(data.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        applyCompanyData(company, data);
        company.setTradeName(normalizeOptional(request.getTradeName()));
        company.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        return toResponse(saveOrConflict(company));
    }

    public void deleteById(UUID id) {
        if (!companyRepository.existsById(id)) {
            throw new NotFoundException("Company not found");
        }

        companyRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return companyRepository.existsById(id);
    }

    private CompanyResponse toResponse(CompanyEntity entity) {
        CompanyResponse response = new CompanyResponse();

        response.setId(entity.getId());
        response.setLegalName(entity.getLegalName());
        response.setTradeName(entity.getTradeName());
        response.setCnpj(entity.getCnpj());
        response.setCountry(entity.getCountry());
        response.setZipCode(entity.getZipCode());
        response.setCity(entity.getCity());
        response.setState(entity.getState());
        response.setStatusKyb(entity.getKybStatus());
        response.setStatusAml(entity.getAmlStatus());
        response.setAvailableBalanceBrl(entity.getAvailableBalanceBrl());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());

        return response;
    }

    private void applyCompanyData(CompanyEntity company, CompanyNormalizedData data) {
        company.setLegalName(data.legalName());
        company.setCnpj(data.cnpj());
        company.setCountry(data.country());
        company.setZipCode(data.zipCode());
        company.setCity(data.city());
        company.setState(data.state());
    }

    private String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private CompanyEntity saveOrConflict(CompanyEntity company) {
        try {
            return companyRepository.saveAndFlush(company);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("CNPJ já cadastrado");
        }
    }
}
