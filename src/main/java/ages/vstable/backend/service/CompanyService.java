package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanySummaryResponse;
import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.dto.company.ComplianceDocumentResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.ComplianceDocumentEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ComplianceDocumentRepository complianceDocumentRepository;
    private final CompanyDataValidator companyDataValidator;
    private final UserRepository userRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final AveniaKycVerificationRepository verificationRepository;

    public List<CompanyResponse> findAll() {
        return companyRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * A lista administrativa deve apontar para o responsável com acesso vigente à empresa,
     * nunca para o usuário mais antigo associado ao companyId legado.
     */
    @Transactional(readOnly = true)
    public List<CompanySummaryResponse> findSummaries() {
        List<CompanyMembershipEntity> activeOwners = companyMembershipRepository
                .findByRoleAndStatus(CompanyRole.OWNER, MembershipStatus.ACTIVE);

        Map<UUID, UserEntity> usersById = new HashMap<>();
        for (UserEntity user : userRepository.findAllById(
                activeOwners.stream().map(CompanyMembershipEntity::getUserId).distinct().toList())) {
            usersById.put(user.getId(), user);
        }

        Map<UUID, UserEntity> accountOwners = new HashMap<>();
        activeOwners.stream()
                .sorted(Comparator.comparing(CompanyMembershipEntity::getJoinedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyMembershipEntity::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(membership -> {
                    UserEntity owner = usersById.get(membership.getUserId());
                    if (owner != null) {
                        accountOwners.putIfAbsent(membership.getCompanyId(), owner);
                    }
                });

        Map<UUID, ComplianceStatus> statuses = latestKycStatuses(accountOwners.values().stream().map(UserEntity::getId).toList());
        return companyRepository.findAll().stream()
                .sorted(Comparator.comparing(CompanyEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(company -> {
                    UserEntity owner = accountOwners.get(company.getId());
                    return toSummary(company, owner, owner == null ? ComplianceStatus.PENDING
                            : statuses.getOrDefault(owner.getId(), ComplianceStatus.PENDING));
                })
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

    @Transactional
    public CompanyResponse create(CompanyCreateRequest request) {
        CompanyNormalizedData data = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(), request.getZipCode(),
                request.getCity(), request.getState());

        assertCnpjAvailable(data.cnpj());

        var saved = saveNewCompany(data, request.getTradeName(), OffsetDateTime.now(ZoneOffset.UTC));
        ages.vstable.backend.logging.OperationalLog.milestone("company.creation.completed", "success", "company", saved.getId());
        return toResponse(saved);
    }

    @Transactional
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

        var saved = saveOrConflict(company);
        ages.vstable.backend.logging.OperationalLog.milestone("company.update.completed", "success", "company", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void deleteById(UUID id) {
        if (!companyRepository.existsById(id)) {
            throw new NotFoundException("Company not found");
        }

        companyRepository.deleteById(id);
        ages.vstable.backend.logging.OperationalLog.milestone("company.deletion.completed", "success", "company", id);
    }

    public boolean existsById(UUID id) {
        return companyRepository.existsById(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyComplianceStatusResponse getComplianceStatus(UUID id) {
        CompanyEntity company = companyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));

        List<ComplianceDocumentEntity> documents = complianceDocumentRepository.findByCompanyId(id);

        if (documents.stream().anyMatch(document -> document.getStatus() == null)) {
            throw new UnprocessableEntityException("Company has an invalid compliance status");
        }

        CompanyComplianceStatusResponse response = new CompanyComplianceStatusResponse();
        response.setId(company.getId());
        response.setLegalName(company.getLegalName());
        response.setTradeName(company.getTradeName());
        response.setCnpj(company.getCnpj());
        var owners = companyMembershipRepository.findByCompanyIdAndStatus(id, MembershipStatus.ACTIVE).stream()
                .filter(member -> member.getRole() == CompanyRole.OWNER)
                .sorted(Comparator.comparing(CompanyMembershipEntity::getJoinedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyMembershipEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        response.setOverallStatus(owners.isEmpty() ? ComplianceStatus.PENDING
                : latestKycStatuses(List.of(owners.get(0).getUserId()))
                        .getOrDefault(owners.get(0).getUserId(), ComplianceStatus.PENDING));
        response.setDocuments(documents.stream().map(this::toDocumentResponse).toList());

        return response;
    }

    private Map<UUID, ComplianceStatus> latestKycStatuses(List<UUID> userIds) {
        Map<UUID, ComplianceStatus> statuses = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (AveniaKycVerificationEntity verification : verificationRepository.findByUserIdInOrderByCreatedAtDesc(userIds)) {
                statuses.putIfAbsent(verification.getUserId(), verification.getStatus() == null
                        ? ComplianceStatus.PENDING : verification.getStatus());
            }
        }
        return statuses;
    }

    private CompanySummaryResponse toSummary(CompanyEntity company, UserEntity representative, ComplianceStatus status) {
        CompanySummaryResponse summary = new CompanySummaryResponse();
        summary.setId(company.getId());
        summary.setLegalName(company.getLegalName());
        summary.setTradeName(company.getTradeName());
        summary.setCnpj(company.getCnpj());
        summary.setCity(company.getCity());
        summary.setState(company.getState());
        summary.setOverallStatus(status);
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

    /**
     * Persiste uma empresa nova. Um CNPJ gravado por outra requisição depois da
     * verificação prévia é decidido pela restrição única do banco e vira conflito.
     */
    CompanyEntity saveNewCompany(CompanyNormalizedData data, String tradeName, OffsetDateTime now) {
        return saveOrConflict(buildNewCompany(data, tradeName, now));
    }

    private CompanyEntity buildNewCompany(CompanyNormalizedData data, String tradeName, OffsetDateTime now) {
        CompanyEntity company = new CompanyEntity();

        applyCompanyData(company, data);
        company.setTradeName(normalizeOptional(tradeName));

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
