package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanySummaryResponse;
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
import ages.vstable.backend.entity.enums.DocumentType;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.ComplianceDocumentRepository;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ComplianceDocumentRepository complianceDocumentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    @Mock
    private AveniaKycVerificationRepository verificationRepository;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository, complianceDocumentRepository, new CompanyDataValidator(), userRepository, companyMembershipRepository, verificationRepository);
    }

    @Test
    void create_normalizesAndMapsNewFields() {
        CompanyCreateRequest request = validRequest();
        request.setTradeName(" Nome Fantasia ");
        request.setCity(" Porto Alegre ");
        UUID companyId = UUID.randomUUID();
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });

        CompanyResponse response = companyService.create(request);

        assertThat(response.getId()).isEqualTo(companyId);
        assertThat(response.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(response.getTradeName()).isEqualTo("Nome Fantasia");
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getZipCode()).isEqualTo("90000000");
        assertThat(response.getCountry()).isEqualTo("Brasil");
        assertThat(response.getCity()).isEqualTo("Porto Alegre");
        assertThat(response.getState()).isEqualTo("RS");
        assertThat(response.getAvailableBalanceBrl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getUpdatedAt()).isNotNull();

        ArgumentCaptor<CompanyEntity> captor = ArgumentCaptor.forClass(CompanyEntity.class);
        verify(companyRepository).saveAndFlush(captor.capture());
        CompanyEntity saved = captor.getValue();
        assertThat(saved.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(saved.getTradeName()).isEqualTo("Nome Fantasia");
        assertThat(saved.getCity()).isEqualTo("Porto Alegre");
        assertThat(saved.getAvailableBalanceBrl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void create_existingCnpjOrConstraintRace_returnsConflict() {
        CompanyCreateRequest request = validRequest();
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);

        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(false);
        when(companyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"companies_cnpj_key\""));
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_otherConstraintViolation_isNotReportedAsCnpjConflict() {
        CompanyCreateRequest request = validRequest();
        when(companyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying(50)"));

        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findSummaries_usesActiveOwnerInsteadOfOldestLegacyUser() {
        UUID companyId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime updatedAt = createdAt.plusDays(1);
        CompanyEntity company = buildCompany(companyId);
        company.setTradeName("Empresa Fantasia");
        company.setCity("Porto Alegre");
        company.setState("RS");
        company.setCreatedAt(createdAt);
        company.setUpdatedAt(updatedAt);

        UserEntity owner = UserEntity.builder().id(UUID.randomUUID()).companyId(null)
                .fullName("Gestora Ativa").email("gestora@x.com").createdAt(createdAt.plusDays(3)).build();
        CompanyMembershipEntity activeOwner = CompanyMembershipEntity.builder()
                .id(UUID.randomUUID()).companyId(companyId).userId(owner.getId())
                .role(CompanyRole.OWNER).status(MembershipStatus.ACTIVE).joinedAt(createdAt.plusDays(3)).build();

        when(companyRepository.findAll()).thenReturn(List.of(company));
        when(companyMembershipRepository.findByRoleAndStatus(CompanyRole.OWNER, MembershipStatus.ACTIVE))
                .thenReturn(List.of(activeOwner));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));
        when(verificationRepository.findByUserIdInOrderByCreatedAtDesc(List.of(owner.getId())))
                .thenReturn(List.of(
                        AveniaKycVerificationEntity.builder().userId(owner.getId()).status(ComplianceStatus.APPROVED).build(),
                        AveniaKycVerificationEntity.builder().userId(owner.getId()).status(ComplianceStatus.REJECTED).build()));

        CompanySummaryResponse summary = companyService.findSummaries().get(0);

        assertThat(summary.getId()).isEqualTo(companyId);
        assertThat(summary.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(summary.getTradeName()).isEqualTo("Empresa Fantasia");
        assertThat(summary.getCnpj()).isEqualTo("11222333000181");
        assertThat(summary.getCity()).isEqualTo("Porto Alegre");
        assertThat(summary.getState()).isEqualTo("RS");
        assertThat(summary.getOverallStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(summary.getCreatedAt()).isEqualTo(createdAt);
        assertThat(summary.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(summary.getRepresentativeId()).isEqualTo(owner.getId());
        assertThat(summary.getRepresentativeName()).isEqualTo("Gestora Ativa");
        assertThat(summary.getRepresentativeEmail()).isEqualTo("gestora@x.com");
        verify(companyMembershipRepository).findByRoleAndStatus(CompanyRole.OWNER, MembershipStatus.ACTIVE);
        verify(userRepository, never()).findAll();
    }

    @Test
    void findSummaries_ignoresMissingOwnerIdentityAndLegacyCompanyId() {
        UUID firstCompanyId = UUID.randomUUID();
        UUID secondCompanyId = UUID.randomUUID();
        CompanyEntity first = buildCompany(firstCompanyId);
        CompanyEntity second = buildCompany(secondCompanyId);

        // A associação correta é o vínculo ativo, não o companyId legado do usuário.
        UserEntity owner = UserEntity.builder().id(UUID.randomUUID()).companyId(secondCompanyId)
                .fullName("Responsável Atual").email("owner@x.com").build();
        CompanyMembershipEntity firstOwner = CompanyMembershipEntity.builder()
                .id(UUID.randomUUID()).companyId(firstCompanyId).userId(owner.getId())
                .role(CompanyRole.OWNER).status(MembershipStatus.ACTIVE)
                .joinedAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        CompanyMembershipEntity orphanOwner = CompanyMembershipEntity.builder()
                .id(UUID.randomUUID()).companyId(secondCompanyId).userId(UUID.randomUUID())
                .role(CompanyRole.OWNER).status(MembershipStatus.ACTIVE)
                .joinedAt(OffsetDateTime.now(ZoneOffset.UTC)).build();

        when(companyRepository.findAll()).thenReturn(List.of(first, second));
        when(companyMembershipRepository.findByRoleAndStatus(CompanyRole.OWNER, MembershipStatus.ACTIVE))
                .thenReturn(List.of(firstOwner, orphanOwner));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));

        List<CompanySummaryResponse> summaries = companyService.findSummaries();
        CompanySummaryResponse firstSummary = summaries.stream()
                .filter(summary -> firstCompanyId.equals(summary.getId())).findFirst().orElseThrow();
        CompanySummaryResponse secondSummary = summaries.stream()
                .filter(summary -> secondCompanyId.equals(summary.getId())).findFirst().orElseThrow();

        assertThat(firstSummary.getRepresentativeId()).isEqualTo(owner.getId());
        assertThat(secondSummary.getRepresentativeId()).isNull();
        assertThat(secondSummary.getRepresentativeName()).isNull();
        assertThat(secondSummary.getOverallStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void findSummaries_companyWithoutActiveOwner_hasNoRepresentative() {
        CompanyEntity company = buildCompany(UUID.randomUUID());
        when(companyRepository.findAll()).thenReturn(List.of(company));
        when(companyMembershipRepository.findByRoleAndStatus(CompanyRole.OWNER, MembershipStatus.ACTIVE))
                .thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        CompanySummaryResponse summary = companyService.findSummaries().get(0);

        assertThat(summary.getRepresentativeId()).isNull();
        assertThat(summary.getRepresentativeName()).isNull();
        assertThat(summary.getOverallStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void findAll_returnsAllCompaniesMapped() {
        CompanyEntity company = buildCompany(UUID.randomUUID());
        when(companyRepository.findAll()).thenReturn(List.of(company));

        List<CompanyResponse> result = companyService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(company.getId());
        assertThat(result.get(0).getCnpj()).isEqualTo("11222333000181");
    }

    @Test
    void findAll_noCompaniesRegistered_returnsEmptyList() {
        when(companyRepository.findAll()).thenReturn(List.of());

        assertThat(companyService.findAll()).isEmpty();
    }

    @Test
    void findById_returnsCompany_whenExists() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id);
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));

        Optional<CompanyResponse> result = companyService.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }

    @Test
    void findById_returnsEmpty_whenCompanyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(companyService.findById(id)).isEmpty();
    }

    @Test
    void findByCnpj_returnsCompanyWhenExistsAndEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id);
        when(companyRepository.findByCnpj("11222333000181")).thenReturn(Optional.of(company));
        when(companyRepository.findByCnpj("00000000000191")).thenReturn(Optional.empty());

        Optional<CompanyResponse> found = companyService.findByCnpj("11222333000181");
        Optional<CompanyResponse> missing = companyService.findByCnpj("00000000000191");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(found.get().getCnpj()).isEqualTo("11222333000181");
        assertThat(missing).isEmpty();
    }

    @Test
    void update_appliesNewDataAndKeepsSameCnpj_withoutCheckingConflict() {
        UUID id = UUID.randomUUID();
        CompanyEntity existing = buildCompany(id);
        existing.setTradeName("Nome Antigo");
        when(companyRepository.findById(id)).thenReturn(Optional.of(existing));
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyUpdateRequest request = validUpdateRequest("11.222.333/0001-81");
        request.setTradeName("Novo Nome");

        CompanyResponse response = companyService.update(id, request);

        assertThat(response.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(response.getTradeName()).isEqualTo("Novo Nome");
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getCity()).isEqualTo("Porto Alegre");
        assertThat(response.getUpdatedAt()).isNotNull();
        verify(companyRepository, never()).existsByCnpj(any());
    }

    @Test
    void update_throwsNotFound_whenCompanyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> companyService.update(id, validUpdateRequest("11.222.333/0001-81")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_throwsConflict_whenNewCnpjBelongsToAnotherCompany() {
        UUID id = UUID.randomUUID();
        CompanyEntity existing = buildCompany(id);
        when(companyRepository.findById(id)).thenReturn(Optional.of(existing));
        when(companyRepository.existsByCnpj("12345678000195")).thenReturn(true);

        CompanyUpdateRequest request = validUpdateRequest("12.345.678/0001-95");

        assertThatThrownBy(() -> companyService.update(id, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_allowsCnpjChange_whenNewCnpjIsAvailable() {
        UUID id = UUID.randomUUID();
        CompanyEntity existing = buildCompany(id);
        when(companyRepository.findById(id)).thenReturn(Optional.of(existing));
        when(companyRepository.existsByCnpj("12345678000195")).thenReturn(false);
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyResponse response = companyService.update(id, validUpdateRequest("12.345.678/0001-95"));

        assertThat(response.getCnpj()).isEqualTo("12345678000195");
    }

    @Test
    void deleteById_deletesCompany_whenExists() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);

        companyService.deleteById(id);

        verify(companyRepository).deleteById(id);
    }

    @Test
    void deleteById_throwsNotFound_whenCompanyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> companyService.deleteById(id))
                .isInstanceOf(NotFoundException.class);

        verify(companyRepository, never()).deleteById(any());
    }

    @Test
    void existsById_delegatesToRepository() {
        UUID existingId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(companyRepository.existsById(existingId)).thenReturn(true);
        when(companyRepository.existsById(missingId)).thenReturn(false);

        assertThat(companyService.existsById(existingId)).isTrue();
        assertThat(companyService.existsById(missingId)).isFalse();
    }

    @Test
    void getComplianceStatus_returnsStatusAndDocuments_whenCompanyExists() {
        UUID id = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        OffsetDateTime uploadedAt = OffsetDateTime.of(2026, 2, 10, 14, 0, 0, 0, ZoneOffset.UTC);
        CompanyEntity company = buildCompany(id);
        company.setTradeName("Empresa Fantasia");
        ComplianceDocumentEntity document = ComplianceDocumentEntity.builder()
                .id(documentId)
                .company(company)
                .documentType(DocumentType.ARTICLES_OF_INCORPORATION)
                .fileName("contrato-social.pdf")
                .fileSizeBytes(2048L)
                .status(ComplianceStatus.APPROVED)
                .uploadedAt(uploadedAt)
                .build();

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(complianceDocumentRepository.findByCompanyId(id)).thenReturn(List.of(document));

        CompanyComplianceStatusResponse response = companyService.getComplianceStatus(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(response.getTradeName()).isEqualTo("Empresa Fantasia");
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getOverallStatus()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(response.getDocuments()).hasSize(1);
        ComplianceDocumentResponse docResponse = response.getDocuments().get(0);
        assertThat(docResponse.getId()).isEqualTo(documentId);
        assertThat(docResponse.getDocumentType()).isEqualTo(DocumentType.ARTICLES_OF_INCORPORATION);
        assertThat(docResponse.getFileName()).isEqualTo("contrato-social.pdf");
        assertThat(docResponse.getFileSizeBytes()).isEqualTo(2048L);
        assertThat(docResponse.getStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(docResponse.getUploadedAt()).isEqualTo(uploadedAt);
    }

    @Test
    void getComplianceStatus_throwsNotFound_whenCompanyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> companyService.getComplianceStatus(id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getComplianceStatus_throwsUnprocessable_whenDocumentStatusIsNull() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id);
        ComplianceDocumentEntity document = ComplianceDocumentEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .status(null)
                .build();

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(complianceDocumentRepository.findByCompanyId(id)).thenReturn(List.of(document));

        assertThatThrownBy(() -> companyService.getComplianceStatus(id))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ComplianceStatus.class)
    void getComplianceStatus_usesLatestIndividualKycOfActiveOwner(ComplianceStatus status) {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var owner = CompanyMembershipEntity.builder().id(UUID.randomUUID()).companyId(id).userId(userId)
                .role(CompanyRole.OWNER).status(MembershipStatus.ACTIVE).build();
        when(companyRepository.findById(id)).thenReturn(Optional.of(buildCompany(id)));
        when(companyMembershipRepository.findByCompanyIdAndStatus(id, MembershipStatus.ACTIVE))
                .thenReturn(List.of(owner));
        when(verificationRepository.findByUserIdInOrderByCreatedAtDesc(List.of(userId))).thenReturn(List.of(
                AveniaKycVerificationEntity.builder().userId(userId).status(status).build(),
                AveniaKycVerificationEntity.builder().userId(userId).status(ComplianceStatus.APPROVED).build()));

        var response = companyService.getComplianceStatus(id);

        assertThat(response.getOverallStatus()).isEqualTo(status);
    }

    private CompanyEntity buildCompany(UUID id) {
        CompanyEntity company = new CompanyEntity();
        company.setId(id);
        company.setLegalName("Empresa Legada Ltda");
        company.setCnpj("11222333000181");
        return company;
    }

    private CompanyCreateRequest validRequest() {
        CompanyCreateRequest request = new CompanyCreateRequest();
        request.setLegalName(" Empresa Legada Ltda ");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry(" Brasil ");
        request.setZipCode("90000-000");
        request.setCity(" ");
        request.setState(" RS ");
        return request;
    }

    private CompanyUpdateRequest validUpdateRequest(String cnpj) {
        CompanyUpdateRequest request = new CompanyUpdateRequest();
        request.setLegalName("Empresa Legada Ltda");
        request.setCnpj(cnpj);
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }
}
