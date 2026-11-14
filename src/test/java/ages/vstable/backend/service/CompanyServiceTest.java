package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanyComplianceUpdateRequest;
import ages.vstable.backend.dto.company.CompanySummaryResponse;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.dto.company.ComplianceDocumentResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ComplianceDocumentEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.DocumentType;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.CompanyRepository;
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

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository, complianceDocumentRepository, new CompanyDataValidator(), userRepository);
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
        assertThat(response.getStatusKyb()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(response.getStatusAml()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(response.getAvailableBalanceBrl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getUpdatedAt()).isNotNull();

        ArgumentCaptor<CompanyEntity> captor = ArgumentCaptor.forClass(CompanyEntity.class);
        verify(companyRepository).saveAndFlush(captor.capture());
        CompanyEntity saved = captor.getValue();
        assertThat(saved.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(saved.getTradeName()).isEqualTo("Nome Fantasia");
        assertThat(saved.getCity()).isEqualTo("Porto Alegre");
        assertThat(saved.getKybStatus()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(saved.getAmlStatus()).isEqualTo(ComplianceStatus.PENDING);
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
    void findSummaries_usesEarliestUserAsRepresentativeAndComputesOverallStatus() {
        UUID companyId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime updatedAt = OffsetDateTime.of(2026, 1, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        CompanyEntity company = buildCompany(companyId, ComplianceStatus.APPROVED, ComplianceStatus.UNDER_REVIEW);
        company.setTradeName("Empresa Fantasia");
        company.setCity("Porto Alegre");
        company.setState("RS");
        company.setCreatedAt(createdAt);
        company.setUpdatedAt(updatedAt);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity later = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Segunda Pessoa").email("b@x.com").createdAt(now).build();
        UserEntity first = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Primeira Pessoa").email("a@x.com").createdAt(now.minusDays(1)).build();
        UserEntity latest = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Terceira Pessoa").email("c@x.com").createdAt(now.plusDays(1)).build();
        UserEntity withoutCreatedAt = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Sem Data").email("d@x.com").createdAt(null).build();

        when(companyRepository.findAll()).thenReturn(List.of(company));
        when(userRepository.findAll()).thenReturn(List.of(later, first, latest, withoutCreatedAt));

        List<CompanySummaryResponse> result = companyService.findSummaries();

        assertThat(result).hasSize(1);
        CompanySummaryResponse summary = result.get(0);
        assertThat(summary.getId()).isEqualTo(companyId);
        assertThat(summary.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(summary.getTradeName()).isEqualTo("Empresa Fantasia");
        assertThat(summary.getCnpj()).isEqualTo("11222333000181");
        assertThat(summary.getCity()).isEqualTo("Porto Alegre");
        assertThat(summary.getState()).isEqualTo("RS");
        assertThat(summary.getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(summary.getStatusAml()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
        assertThat(summary.getOverallStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
        assertThat(summary.getCreatedAt()).isEqualTo(createdAt);
        assertThat(summary.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(summary.getRepresentativeId()).isEqualTo(first.getId());
        assertThat(summary.getRepresentativeName()).isEqualTo("Primeira Pessoa");
        assertThat(summary.getRepresentativeEmail()).isEqualTo("a@x.com");
    }

    @Test
    void findSummaries_replacesRepresentativeWithoutCreatedAtWhenCandidateHasTimestampAndIgnoresUnlinkedUsers() {
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = buildCompany(companyId, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
        CompanyEntity unpersistedCompany = buildCompany(null, ComplianceStatus.PENDING, ComplianceStatus.PENDING);

        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity withoutCreatedAt = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Sem Data").email("null@x.com").createdAt(null).build();
        UserEntity withCreatedAt = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Com Data").email("dated@x.com").createdAt(timestamp).build();
        UserEntity sameTimestamp = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("Mesma Data").email("same@x.com").createdAt(timestamp).build();
        UserEntity unlinkedUser = UserEntity.builder().id(UUID.randomUUID()).companyId(null)
                .fullName("Sem Empresa").email("orphan@x.com").createdAt(timestamp.minusDays(5)).build();

        when(companyRepository.findAll()).thenReturn(List.of(company, unpersistedCompany));
        when(userRepository.findAll()).thenReturn(List.of(withoutCreatedAt, withCreatedAt, sameTimestamp, unlinkedUser));

        List<CompanySummaryResponse> summaries = companyService.findSummaries();

        assertThat(summaries).hasSize(2);
        CompanySummaryResponse linkedSummary = summaries.stream()
                .filter(s -> companyId.equals(s.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(linkedSummary.getRepresentativeId()).isEqualTo(withCreatedAt.getId());
        assertThat(linkedSummary.getRepresentativeName()).isEqualTo("Com Data");

        CompanySummaryResponse unpersistedSummary = summaries.stream()
                .filter(s -> s.getId() == null)
                .findFirst()
                .orElseThrow();
        assertThat(unpersistedSummary.getRepresentativeId()).isNull();
        assertThat(unpersistedSummary.getRepresentativeName()).isNull();
    }

    @Test
    void findSummaries_companyWithoutUsers_hasNoRepresentative() {
        CompanyEntity company = buildCompany(UUID.randomUUID(), ComplianceStatus.PENDING, ComplianceStatus.PENDING);
        when(companyRepository.findAll()).thenReturn(List.of(company));
        when(userRepository.findAll()).thenReturn(List.of());

        CompanySummaryResponse summary = companyService.findSummaries().get(0);

        assertThat(summary.getRepresentativeId()).isNull();
        assertThat(summary.getRepresentativeName()).isNull();
        assertThat(summary.getOverallStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void updateComplianceStatus_updatesOnlyInformedStatuses() {
        UUID companyId = UUID.randomUUID();
        OffsetDateTime previousUpdatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        CompanyEntity company = buildCompany(companyId, ComplianceStatus.PENDING, ComplianceStatus.PENDING);
        company.setUpdatedAt(previousUpdatedAt);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyComplianceUpdateRequest kybRequest = new CompanyComplianceUpdateRequest();
        kybRequest.setStatusKyb(ComplianceStatus.APPROVED);

        CompanyResponse kybResponse = companyService.updateComplianceStatus(companyId, kybRequest);

        assertThat(kybResponse.getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(kybResponse.getStatusAml()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(kybResponse.getUpdatedAt()).isNotNull().isAfter(previousUpdatedAt);

        CompanyComplianceUpdateRequest amlRequest = new CompanyComplianceUpdateRequest();
        amlRequest.setStatusAml(ComplianceStatus.UNDER_REVIEW);

        CompanyResponse amlResponse = companyService.updateComplianceStatus(companyId, amlRequest);

        assertThat(amlResponse.getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(amlResponse.getStatusAml()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
        assertThat(amlResponse.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateComplianceStatus_withoutAnyStatus_isRejected() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId))
                .thenReturn(Optional.of(buildCompany(companyId, ComplianceStatus.PENDING, ComplianceStatus.PENDING)));

        assertThatThrownBy(() -> companyService.updateComplianceStatus(companyId, new CompanyComplianceUpdateRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(companyRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateComplianceStatus_unknownCompany_throwsNotFound() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        CompanyComplianceUpdateRequest request = new CompanyComplianceUpdateRequest();
        request.setStatusAml(ComplianceStatus.APPROVED);

        assertThatThrownBy(() -> companyService.updateComplianceStatus(companyId, request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findAll_returnsAllCompaniesMapped() {
        CompanyEntity company = buildCompany(UUID.randomUUID(), ComplianceStatus.APPROVED, ComplianceStatus.PENDING);
        when(companyRepository.findAll()).thenReturn(List.of(company));

        List<CompanyResponse> result = companyService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(company.getId());
        assertThat(result.get(0).getCnpj()).isEqualTo("11222333000181");
        assertThat(result.get(0).getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(result.get(0).getStatusAml()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void findAll_noCompaniesRegistered_returnsEmptyList() {
        when(companyRepository.findAll()).thenReturn(List.of());

        assertThat(companyService.findAll()).isEmpty();
    }

    @Test
    void findById_returnsCompany_whenExists() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
        CompanyEntity company = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
        CompanyEntity existing = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
        CompanyEntity existing = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
        when(companyRepository.findById(id)).thenReturn(Optional.of(existing));
        when(companyRepository.existsByCnpj("12345678000195")).thenReturn(true);

        CompanyUpdateRequest request = validUpdateRequest("12.345.678/0001-95");

        assertThatThrownBy(() -> companyService.update(id, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_allowsCnpjChange_whenNewCnpjIsAvailable() {
        UUID id = UUID.randomUUID();
        CompanyEntity existing = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
        CompanyEntity company = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
        assertThat(response.getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(response.getStatusAml()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(response.getOverallStatus()).isEqualTo(ComplianceStatus.APPROVED);
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
    void getComplianceStatus_throwsUnprocessable_whenKybStatusIsNull() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id, null, ComplianceStatus.APPROVED);
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> companyService.getComplianceStatus(id))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void getComplianceStatus_throwsUnprocessable_whenDocumentStatusIsNull() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
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
    @MethodSource("overallStatusCombinations")
    void getComplianceStatus_computesOverallStatusForEveryCombination(
            ComplianceStatus kyb, ComplianceStatus aml, ComplianceStatus expected) {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id, kyb, aml);

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(complianceDocumentRepository.findByCompanyId(id)).thenReturn(List.of());

        CompanyComplianceStatusResponse response = companyService.getComplianceStatus(id);

        assertThat(response.getOverallStatus()).isEqualTo(expected);
    }

    private static Stream<Arguments> overallStatusCombinations() {
        return Stream.of(
                Arguments.of(ComplianceStatus.PENDING, ComplianceStatus.PENDING, ComplianceStatus.PENDING),
                Arguments.of(ComplianceStatus.PENDING, ComplianceStatus.UNDER_REVIEW, ComplianceStatus.UNDER_REVIEW),
                Arguments.of(ComplianceStatus.PENDING, ComplianceStatus.APPROVED, ComplianceStatus.PENDING),
                Arguments.of(ComplianceStatus.PENDING, ComplianceStatus.REJECTED, ComplianceStatus.REJECTED),
                Arguments.of(ComplianceStatus.APPROVED, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED),
                Arguments.of(ComplianceStatus.UNDER_REVIEW, ComplianceStatus.REJECTED, ComplianceStatus.REJECTED),
                Arguments.of(ComplianceStatus.REJECTED, ComplianceStatus.REJECTED, ComplianceStatus.REJECTED)
        );
    }

    private CompanyEntity buildCompany(UUID id, ComplianceStatus kybStatus, ComplianceStatus amlStatus) {
        CompanyEntity company = new CompanyEntity();
        company.setId(id);
        company.setLegalName("Empresa Legada Ltda");
        company.setCnpj("11222333000181");
        company.setKybStatus(kybStatus);
        company.setAmlStatus(amlStatus);
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
