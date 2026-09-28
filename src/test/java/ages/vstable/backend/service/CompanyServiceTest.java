package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ComplianceDocumentEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ComplianceDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ComplianceDocumentRepository complianceDocumentRepository;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository, complianceDocumentRepository, new CompanyDataValidator());
    }

    @Test
    void create_normalizesAndMapsNewFields() {
        CompanyCreateRequest request = validRequest();
        UUID companyId = UUID.randomUUID();
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });

        CompanyResponse response = companyService.create(request);

        assertThat(response.getId()).isEqualTo(companyId);
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getZipCode()).isEqualTo("90000000");
        assertThat(response.getCountry()).isEqualTo("Brasil");
        assertThat(response.getCity()).isNull();
        assertThat(response.getState()).isEqualTo("RS");
        assertThat(response.getUpdatedAt()).isNotNull();

        ArgumentCaptor<CompanyEntity> captor = ArgumentCaptor.forClass(CompanyEntity.class);
        verify(companyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLegalName()).isEqualTo("Empresa Legada Ltda");
    }

    @Test
    void create_existingCnpjOrConstraintRace_returnsConflict() {
        CompanyCreateRequest request = validRequest();
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);

        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(false);
        when(companyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);
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
    void update_appliesNewDataAndKeepsSameCnpj_withoutCheckingConflict() {
        UUID id = UUID.randomUUID();
        CompanyEntity existing = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);
        existing.setTradeName("Nome Antigo");
        when(companyRepository.findById(id)).thenReturn(Optional.of(existing));
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyUpdateRequest request = validUpdateRequest("11.222.333/0001-81");
        request.setTradeName("Novo Nome");

        CompanyResponse response = companyService.update(id, request);

        assertThat(response.getTradeName()).isEqualTo("Novo Nome");
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
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
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);

        assertThat(companyService.existsById(id)).isTrue();
    }

    @Test
    void getComplianceStatus_returnsStatusAndDocuments_whenCompanyExists() {
        UUID id = UUID.randomUUID();
        CompanyEntity company = buildCompany(id, ComplianceStatus.APPROVED, ComplianceStatus.APPROVED);

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(complianceDocumentRepository.findByCompanyId(id)).thenReturn(List.of());

        CompanyComplianceStatusResponse response = companyService.getComplianceStatus(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getLegalName()).isEqualTo("Empresa Legada Ltda");
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getStatusKyb()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(response.getStatusAml()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(response.getOverallStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(response.getDocuments()).isEmpty();
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
