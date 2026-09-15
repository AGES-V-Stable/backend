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
    void create_normalizaEMapeiaNovosCampos() {
        CompanyCreateRequest request = requestValido();
        UUID empresaId = UUID.randomUUID();
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity empresa = invocation.getArgument(0);
            empresa.setId(empresaId);
            return empresa;
        });

        CompanyResponse response = companyService.create(request);

        assertThat(response.getId()).isEqualTo(empresaId);
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getCep()).isEqualTo("90000000");
        assertThat(response.getPais()).isEqualTo("Brasil");
        assertThat(response.getCidade()).isNull();
        assertThat(response.getEstado()).isEqualTo("RS");
        assertThat(response.getAtualizadoEm()).isNotNull();

        ArgumentCaptor<CompanyEntity> captor = ArgumentCaptor.forClass(CompanyEntity.class);
        verify(companyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRazaoSocial()).isEqualTo("Empresa Legada Ltda");
    }

    @Test
    void create_cnpjExistenteOuCorridaNaConstraint_retornaConflict() {
        CompanyCreateRequest request = requestValido();
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);

        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(false);
        when(companyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        assertThatThrownBy(() -> companyService.create(request))
                .isInstanceOf(ConflictException.class);
    }

    private CompanyCreateRequest requestValido() {
        CompanyCreateRequest request = new CompanyCreateRequest();
        request.setRazaoSocial(" Empresa Legada Ltda ");
        request.setCnpj("11.222.333/0001-81");
        request.setPais(" Brasil ");
        request.setCep("90000-000");
        request.setCidade(" ");
        request.setEstado(" RS ");
        return request;
    }
}
