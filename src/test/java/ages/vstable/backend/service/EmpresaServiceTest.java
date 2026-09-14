package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.EmpresaCreateRequest;
import ages.vstable.backend.dto.empresa.EmpresaResponse;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.EmpresaRepository;
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
class EmpresaServiceTest {

    @Mock
    private EmpresaRepository empresaRepository;

    private EmpresaService empresaService;

    @BeforeEach
    void setUp() {
        empresaService = new EmpresaService(empresaRepository, new EmpresaDadosValidator());
    }

    @Test
    void create_normalizaEMapeiaNovosCampos() {
        EmpresaCreateRequest request = requestValido();
        UUID empresaId = UUID.randomUUID();
        when(empresaRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            EmpresaEntity empresa = invocation.getArgument(0);
            empresa.setId(empresaId);
            return empresa;
        });

        EmpresaResponse response = empresaService.create(request);

        assertThat(response.getId()).isEqualTo(empresaId);
        assertThat(response.getCnpj()).isEqualTo("11222333000181");
        assertThat(response.getCep()).isEqualTo("90000000");
        assertThat(response.getPais()).isEqualTo("Brasil");
        assertThat(response.getCidade()).isNull();
        assertThat(response.getEstado()).isEqualTo("RS");
        assertThat(response.getAtualizadoEm()).isNotNull();

        ArgumentCaptor<EmpresaEntity> captor = ArgumentCaptor.forClass(EmpresaEntity.class);
        verify(empresaRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRazaoSocial()).isEqualTo("Empresa Legada Ltda");
    }

    @Test
    void create_cnpjExistenteOuCorridaNaConstraint_retornaConflict() {
        EmpresaCreateRequest request = requestValido();
        when(empresaRepository.existsByCnpj("11222333000181")).thenReturn(true);
        assertThatThrownBy(() -> empresaService.create(request))
                .isInstanceOf(ConflictException.class);

        when(empresaRepository.existsByCnpj("11222333000181")).thenReturn(false);
        when(empresaRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        assertThatThrownBy(() -> empresaService.create(request))
                .isInstanceOf(ConflictException.class);
    }

    private EmpresaCreateRequest requestValido() {
        EmpresaCreateRequest request = new EmpresaCreateRequest();
        request.setRazaoSocial(" Empresa Legada Ltda ");
        request.setCnpj("11.222.333/0001-81");
        request.setPais(" Brasil ");
        request.setCep("90000-000");
        request.setCidade(" ");
        request.setEstado(" RS ");
        return request;
    }
}
