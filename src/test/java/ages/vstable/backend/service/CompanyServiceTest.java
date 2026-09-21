package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository, new CompanyDataValidator());
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
}
