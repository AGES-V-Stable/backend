package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.CadastroEmpresaRequest;
import ages.vstable.backend.dto.empresa.CadastroEmpresaResponse;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CadastroEmpresaServiceTest {

    private static final String CNPJ_VALIDO = "11.222.333/0001-81";

    @Mock
    private EmpresaRepository empresaRepository;
    @Mock
    private ProgressoCadastroRepository progressoCadastroRepository;
    @Mock
    private UsuarioRepository usuarioRepository;

    private CadastroEmpresaService cadastroEmpresaService;
    private UUID progressoId;
    private UUID usuarioId;
    private ProgressoCadastroEntity progresso;
    private UsuarioEntity usuario;

    @BeforeEach
    void setUp() {
        cadastroEmpresaService = new CadastroEmpresaService(
                empresaRepository,
                progressoCadastroRepository,
                usuarioRepository,
                new EmpresaDadosValidator()
        );
        progressoId = UUID.randomUUID();
        usuarioId = UUID.randomUUID();
        progresso = ProgressoCadastroEntity.builder()
                .id(progressoId)
                .usuarioId(usuarioId)
                .etapaAtual(2)
                .statusGeral(StatusOnboarding.RASCUNHO)
                .build();
        usuario = UsuarioEntity.builder().id(usuarioId).build();
    }

    @Test
    void create_payloadValido_criaEmpresaEVinculaUsuarioEProgresso() {
        CadastroEmpresaRequest request = requestValido();
        UUID empresaId = UUID.randomUUID();
        stubCadastroValido(empresaId);

        CadastroEmpresaResponse response = cadastroEmpresaService.create(progressoId, request);

        assertThat(response.getEmpresaId()).isEqualTo(empresaId);
        assertThat(response.getProgressoCadastroId()).isEqualTo(progressoId);
        assertThat(response.getEtapaAtual()).isEqualTo(3);
        assertThat(response.getProximaEtapa()).isEqualTo("compliance");
        assertThat(response.getAtualizadoEm()).isNotNull();

        ArgumentCaptor<EmpresaEntity> empresaCaptor = ArgumentCaptor.forClass(EmpresaEntity.class);
        verify(empresaRepository).saveAndFlush(empresaCaptor.capture());
        assertThat(empresaCaptor.getValue().getCnpj()).isEqualTo("11222333000181");
        assertThat(empresaCaptor.getValue().getCep()).isEqualTo("90000000");
        assertThat(empresaCaptor.getValue().getCidade()).isEqualTo("Porto Alegre");

        verify(usuarioRepository).save(usuario);
        assertThat(usuario.getEmpresaId()).isEqualTo(empresaId);
        verify(progressoCadastroRepository).save(progresso);
        assertThat(progresso.getEmpresaId()).isEqualTo(empresaId);
        assertThat(progresso.getEtapaAtual()).isEqualTo(3);
    }

    @Test
    void create_semCidade_persisteCidadeNula() {
        CadastroEmpresaRequest request = requestValido();
        request.setCidade("  ");
        stubCadastroValido(UUID.randomUUID());

        cadastroEmpresaService.create(progressoId, request);

        ArgumentCaptor<EmpresaEntity> captor = ArgumentCaptor.forClass(EmpresaEntity.class);
        verify(empresaRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCidade()).isNull();
    }

    @Test
    void create_progressoInexistente_retornaNotFound() {
        when(progressoCadastroRepository.findByIdForUpdate(progressoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_progressoForaDaEtapaOuJaVinculado_retornaConflict() {
        progresso.setEtapaAtual(3);
        when(progressoCadastroRepository.findByIdForUpdate(progressoId)).thenReturn(Optional.of(progresso));

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                .isInstanceOf(ConflictException.class);
        verify(empresaRepository, never()).save(any());
    }

    @Test
    void create_progressoConcluido_retornaConflict() {
        progresso.setStatusGeral(StatusOnboarding.CONCLUIDO);
        when(progressoCadastroRepository.findByIdForUpdate(progressoId)).thenReturn(Optional.of(progresso));

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_cnpjExistente_retornaConflict() {
        stubProgressoEUsuario();
        when(empresaRepository.existsByCnpj("11222333000181")).thenReturn(true);

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CNPJ já cadastrado");
    }

    @Test
    void create_corridaNaConstraintDeCnpj_retornaConflict() {
        stubProgressoEUsuario();
        when(empresaRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CNPJ já cadastrado");
    }

    @Test
    void create_cnpjOuCepInvalidos_retornaUnprocessableEntity() {
        stubProgressoEUsuario();
        CadastroEmpresaRequest cnpjInvalido = requestValido();
        cnpjInvalido.setCnpj("ABC");
        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, cnpjInvalido))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ em formato inválido");

        CadastroEmpresaRequest cepInvalido = requestValido();
        cepInvalido.setCep("9000");
        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, cepInvalido))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CEP em formato inválido");
    }

    @Test
    void create_cnpjComDigitoVerificadorInvalido_retornaUnprocessableEntity() {
        stubProgressoEUsuario();
        CadastroEmpresaRequest request = requestValido();
        request.setCnpj("11.222.333/0001-80");

        assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, request))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ em formato inválido");
    }

    @Test
    void create_cepEstrangeiro_preservaValor() {
        CadastroEmpresaRequest request = requestValido();
        request.setPais("Portugal");
        request.setCep(" 1000-001 ");
        stubCadastroValido(UUID.randomUUID());

        cadastroEmpresaService.create(progressoId, request);

        ArgumentCaptor<EmpresaEntity> captor = ArgumentCaptor.forClass(EmpresaEntity.class);
        verify(empresaRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCep()).isEqualTo("1000-001");
    }

    private void stubCadastroValido(UUID empresaId) {
        stubProgressoEUsuario();
        when(empresaRepository.saveAndFlush(any(EmpresaEntity.class))).thenAnswer(invocation -> {
            EmpresaEntity empresa = invocation.getArgument(0);
            empresa.setId(empresaId);
            return empresa;
        });
    }

    private void stubProgressoEUsuario() {
        when(progressoCadastroRepository.findByIdForUpdate(progressoId)).thenReturn(Optional.of(progresso));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));
    }

    private CadastroEmpresaRequest requestValido() {
        CadastroEmpresaRequest request = new CadastroEmpresaRequest();
        request.setRazaoSocial("Empresa Exemplo Ltda");
        request.setPais(" Brasil ");
        request.setCnpj(CNPJ_VALIDO);
        request.setCep("90000-000");
        request.setCidade(" Porto Alegre ");
        request.setEstado(" RS ");
        return request;
    }
}
