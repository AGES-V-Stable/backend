package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanyComplianceUpdateRequest;
import ages.vstable.backend.dto.company.CompanySummaryResponse;
import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.dto.company.ComplianceDocumentResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ComplianceDocumentEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ComplianceDocumentRepository;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ComplianceDocumentRepository complianceDocumentRepository;
    private final CompanyDataValidator companyDataValidator;
    private final UserRepository userRepository;

    public List<CompanyResponse> findAll() {
        return companyRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanySummaryResponse> findSummaries() {
        Map<UUID, UserEntity> primaryRepresentatives = userRepository.findAll().stream()
                .filter(user -> user.getCompanyId() != null)
                .collect(Collectors.toMap(
                        UserEntity::getCompanyId,
                        Function.identity(),
                        (first, second) -> isCreatedBefore(second, first) ? second : first));

        return companyRepository.findAll().stream()
                .sorted(Comparator.comparing(CompanyEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(company -> toSummary(company, primaryRepresentatives.get(company.getId())))
                .toList();
    }

    @Transactional
    public CompanyResponse updateComplianceStatus(UUID id, CompanyComplianceUpdateRequest request) {
        CompanyEntity company = companyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));

        if (request.getStatusKyb() == null && request.getStatusAml() == null) {
            throw new IllegalArgumentException("At least one of statusKyb or statusAml is required");
        }
        if (request.getStatusKyb() != null) {
            company.setKybStatus(request.getStatusKyb());
        }
        if (request.getStatusAml() != null) {
            company.setAmlStatus(request.getStatusAml());
        }
        company.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        return toResponse(companyRepository.saveAndFlush(company));
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

        assertCnpjAvailable(data.cnpj());

        CompanyEntity company = buildNewCompany(data, request.getTradeName(), OffsetDateTime.now(ZoneOffset.UTC));

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
            throw new ConflictException("CNPJ already registered");
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyComplianceStatusResponse getComplianceStatus(UUID id) {
        CompanyEntity company = companyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));

        if (company.getKybStatus() == null || company.getAmlStatus() == null) {
            throw new UnprocessableEntityException("Company has an invalid compliance status");
        }

        List<ComplianceDocumentEntity> documents = complianceDocumentRepository.findByCompanyId(id);

        if (documents.stream().anyMatch(document -> document.getStatus() == null)) {
            throw new UnprocessableEntityException("Company has an invalid compliance status");
        }

        CompanyComplianceStatusResponse response = new CompanyComplianceStatusResponse();
        response.setId(company.getId());
        response.setLegalName(company.getLegalName());
        response.setTradeName(company.getTradeName());
        response.setCnpj(company.getCnpj());
        response.setStatusKyb(company.getKybStatus());
        response.setStatusAml(company.getAmlStatus());
        response.setOverallStatus(computeOverallStatus(company.getKybStatus(), company.getAmlStatus()));
        response.setDocuments(documents.stream().map(this::toDocumentResponse).toList());

        return response;
    }

    private ComplianceStatus computeOverallStatus(ComplianceStatus kyb, ComplianceStatus aml) {
        if (kyb == ComplianceStatus.REJECTED || aml == ComplianceStatus.REJECTED) {
            return ComplianceStatus.REJECTED;
        }
        if (kyb == ComplianceStatus.UNDER_REVIEW || aml == ComplianceStatus.UNDER_REVIEW) {
            return ComplianceStatus.UNDER_REVIEW;
        }
        if (kyb == ComplianceStatus.APPROVED && aml == ComplianceStatus.APPROVED) {
            return ComplianceStatus.APPROVED;
        }
        return ComplianceStatus.PENDING;
    }

    private static boolean isCreatedBefore(UserEntity candidate, UserEntity current) {
        if (candidate.getCreatedAt() == null) return false;
        return current.getCreatedAt() == null || candidate.getCreatedAt().isBefore(current.getCreatedAt());
    }

    private CompanySummaryResponse toSummary(CompanyEntity company, UserEntity representative) {
        CompanySummaryResponse summary = new CompanySummaryResponse();
        summary.setId(company.getId());
        summary.setLegalName(company.getLegalName());
        summary.setTradeName(company.getTradeName());
        summary.setCnpj(company.getCnpj());
        summary.setCity(company.getCity());
        summary.setState(company.getState());
        summary.setStatusKyb(company.getKybStatus());
        summary.setStatusAml(company.getAmlStatus());
        summary.setOverallStatus(company.getKybStatus() == null || company.getAmlStatus() == null
                ? ComplianceStatus.PENDING
                : computeOverallStatus(company.getKybStatus(), company.getAmlStatus()));
        summary.setCreatedAt(company.getCreatedAt());
        summary.setUpdatedAt(company.getUpdatedAt());
        if (representative != null) {
            summary.setRepresentativeId(representative.getId());
            summary.setRepresentativeName(representative.getFullName());
            summary.setRepresentativeEmail(representative.getEmail());
        }
        return summary;
    }

    private ComplianceDocumentResponse toDocumentResponse(ComplianceDocumentEntity entity) {
        ComplianceDocumentResponse response = new ComplianceDocumentResponse();
        response.setId(entity.getId());
        response.setDocumentType(entity.getDocumentType());
        response.setFileName(entity.getFileName());
        response.setFileSizeBytes(entity.getFileSizeBytes());
        response.setStatus(entity.getStatus());
        response.setUploadedAt(entity.getUploadedAt());
        return response;
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

    void assertCnpjAvailable(String cnpj) {
        if (companyRepository.existsByCnpj(cnpj)) {
            throw new ConflictException("CNPJ already registered");
        }
    }

    CompanyEntity buildNewCompany(CompanyNormalizedData data, String tradeName, OffsetDateTime now) {
        CompanyEntity company = new CompanyEntity();

        applyCompanyData(company, data);
        company.setTradeName(normalizeOptional(tradeName));

        company.setKybStatus(ComplianceStatus.PENDING);
        company.setAmlStatus(ComplianceStatus.PENDING);
        company.setAvailableBalanceBrl(BigDecimal.ZERO);
        company.setCreatedAt(now);
        company.setUpdatedAt(now);

        return company;
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
            // Only the CNPJ unique constraint is a conflict; other constraint failures are real errors.
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("companies_cnpj_key")) {
                throw new ConflictException("CNPJ already registered");
            }
            throw ex;
        }
    }
}
