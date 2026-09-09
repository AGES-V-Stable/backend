package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.AcessoCreateRequest;
import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AcessoServiceTest {

    private static final String IDEMPOTENCY_KEY = "11111111-1111-1111-1111-111111111111";
    private static final String SECRET = "segredo-de-teste";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ProgressoCadastroRepository progressoCadastroRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AcessoService acessoService;

    @BeforeEach
    void setUp() {
        acessoService = new AcessoService(
                usuarioRepository,
                progressoCadastroRepository,
                transactionTemplate,
                SECRET
        );
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplateToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private AcessoCreateRequest requestValido() {
        AcessoCreateRequest request = new AcessoCreateRequest();
        request.setNomeCompleto("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setSenha("Senha123!");
        request.setConfirmarSenha("Senha123!");
        return request;
    }

    // 1. Payload valido -> cria usuario + progresso_cadastro, token retornado, senha com hash
    @Test
    void create_payloadValido_criaUsuarioEProgressoComSenhaHasheada() {
        AcessoCreateRequest request = requestValido();
        stubTransactionTemplateToRunCallback();
        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(usuarioRepository.existsByEmail(request.getEmail())).thenReturn(false);

        UUID usuarioId = UUID.randomUUID();
        when(usuarioRepository.save(any(UsuarioEntity.class))).thenAnswer(invocation -> {
            UsuarioEntity usuario = invocation.getArgument(0);
            usuario.setId(usuarioId);
            return usuario;
        });

        UUID progressoId = UUID.randomUUID();
        when(progressoCadastroRepository.save(any(ProgressoCadastroEntity.class))).thenAnswer(invocation -> {
            ProgressoCadastroEntity progresso = invocation.getArgument(0);
            progresso.setId(progressoId);
            return progresso;
        });

        AcessoResponse response = acessoService.create(IDEMPOTENCY_KEY, request);

        assertThat(response.getToken()).isEqualTo(progressoId);
        assertThat(response.getNomeCompleto()).isEqualTo(request.getNomeCompleto());
        assertThat(response.getEmail()).isEqualTo(request.getEmail());
        assertThat(response.getEtapaAtual()).isEqualTo(2);

        ArgumentCaptor<UsuarioEntity> usuarioCaptor = ArgumentCaptor.forClass(UsuarioEntity.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        String hashSalvo = usuarioCaptor.getValue().getHashSenha();
        assertThat(hashSalvo).isNotEqualTo(request.getSenha());
        assertThat(hashSalvo).startsWith("$2");

        ArgumentCaptor<ProgressoCadastroEntity> progressoCaptor = ArgumentCaptor.forClass(ProgressoCadastroEntity.class);
        verify(progressoCadastroRepository).save(progressoCaptor.capture());
        assertThat(progressoCaptor.getValue().getUsuarioId()).isEqualTo(usuarioId);
        assertThat(progressoCaptor.getValue().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    // 2. Reenvio com mesma Idempotency-Key + mesmo payload -> retorna igual, sem duplicar
    @Test
    void create_reenvioComMesmaIdempotencyKeyEMesmoPayload_naoDuplicaRegistros() {
        AcessoCreateRequest request = requestValido();

        UUID usuarioId = UUID.randomUUID();
        UUID progressoId = UUID.randomUUID();

        UsuarioEntity usuarioExistente = UsuarioEntity.builder()
                .id(usuarioId)
                .nomeCompleto(request.getNomeCompleto())
                .email(request.getEmail())
                .hashSenha("hash-existente")
                .build();

        ProgressoCadastroEntity progressoExistente = ProgressoCadastroEntity.builder()
                .id(progressoId)
                .usuarioId(usuarioId)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadHash(hashPayloadDoMesmoJeitoQueOServico(request))
                .etapaAtual(2)
                .build();

        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(progressoExistente));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuarioExistente));

        AcessoResponse response = acessoService.create(IDEMPOTENCY_KEY, request);

        assertThat(response.getToken()).isEqualTo(progressoId);
        assertThat(response.getEmail()).isEqualTo(request.getEmail());

        verify(usuarioRepository, never()).save(any());
        verify(progressoCadastroRepository, never()).save(any());
    }

    private String hashPayloadDoMesmoJeitoQueOServico(AcessoCreateRequest request) {
        try {
            String base = request.getNomeCompleto() + "|" + request.getEmail() + "|" + request.getSenha() + "|" + request.getConfirmarSenha();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 3. GET com token valido -> retorna dados salvos
    @Test
    void findById_tokenValido_retornaDadosSalvos() {
        UUID progressoId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();

        ProgressoCadastroEntity progresso = ProgressoCadastroEntity.builder()
                .id(progressoId)
                .usuarioId(usuarioId)
                .etapaAtual(2)
                .build();

        UsuarioEntity usuario = UsuarioEntity.builder()
                .id(usuarioId)
                .nomeCompleto("Joao da Silva")
                .email("joao@example.com")
                .hashSenha("hash-irrelevante")
                .build();

        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.of(progresso));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));

        Optional<AcessoResponse> response = acessoService.findById(progressoId);

        assertThat(response).isPresent();
        assertThat(response.get().getToken()).isEqualTo(progressoId);
        assertThat(response.get().getNomeCompleto()).isEqualTo("Joao da Silva");
        assertThat(response.get().getEmail()).isEqualTo("joao@example.com");
    }

    // 10. GET com token invalido/aleatorio -> vazio (controller traduz para 404)
    @Test
    void findById_tokenInexistente_retornaVazio() {
        UUID tokenAleatorio = UUID.randomUUID();
        when(progressoCadastroRepository.findById(tokenAleatorio)).thenReturn(Optional.empty());

        Optional<AcessoResponse> response = acessoService.findById(tokenAleatorio);

        assertThat(response).isEmpty();
    }

    // 4. E-mail ja existente -> ConflictException (409)
    @Test
    void create_emailJaCadastrado_lancaConflictException() {
        AcessoCreateRequest request = requestValido();
        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(usuarioRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(ConflictException.class);

        verify(usuarioRepository, never()).save(any());
        verify(progressoCadastroRepository, never()).save(any());
    }

    // 5. senha != confirmar_senha -> UnprocessableEntityException (422)
    @Test
    void create_senhaEConfirmacaoDivergentes_lancaUnprocessableEntityException() {
        AcessoCreateRequest request = requestValido();
        request.setConfirmarSenha("OutraSenha123!");

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(UnprocessableEntityException.class);

        verifyNoInteractions(usuarioRepository);
    }

    // 6. Senha fraca -> UnprocessableEntityException (422)
    @Test
    void create_senhaFraca_lancaUnprocessableEntityException() {
        AcessoCreateRequest request = requestValido();
        request.setSenha("fraca");
        request.setConfirmarSenha("fraca");

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(UnprocessableEntityException.class);

        verifyNoInteractions(usuarioRepository);
    }

    // 8. E-mail em formato invalido -> UnprocessableEntityException (422)
    @Test
    void create_emailFormatoInvalido_lancaUnprocessableEntityException() {
        AcessoCreateRequest request = requestValido();
        request.setEmail("joao@@x");

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(UnprocessableEntityException.class);

        verifyNoInteractions(usuarioRepository);
    }

    // Idempotency-Key reutilizada com payload diferente -> ConflictException (409)
    @Test
    void create_idempotencyKeyReutilizadaComPayloadDiferente_lancaConflictException() {
        AcessoCreateRequest request = requestValido();

        ProgressoCadastroEntity progressoExistente = ProgressoCadastroEntity.builder()
                .id(UUID.randomUUID())
                .usuarioId(UUID.randomUUID())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadHash("hash-de-um-payload-diferente")
                .build();

        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(progressoExistente));

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(ConflictException.class);

        verify(usuarioRepository, never()).save(any());
    }

    // 9. Falha simulada de escrita no banco -> nenhuma entidade e' criada (rollback)
    @Test
    void create_falhaAoSalvarUsuario_naoSalvaProgressoENaoEngoleAExcecao() {
        AcessoCreateRequest request = requestValido();
        stubTransactionTemplateToRunCallback();
        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(usuarioRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(usuarioRepository.save(any(UsuarioEntity.class))).thenThrow(new RuntimeException("timeout simulado"));

        assertThatThrownBy(() -> acessoService.create(IDEMPOTENCY_KEY, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("timeout simulado");

        verify(progressoCadastroRepository, never()).save(any());
    }

    // Corrida concorrente: unique constraint da idempotency_key estoura durante o insert
    @Test
    void create_conflitoDeConcorrenciaNaIdempotencyKey_retomaRegistroCriadoPeloConcorrente() {
        AcessoCreateRequest request = requestValido();
        stubTransactionTemplateToRunCallback();

        UUID usuarioId = UUID.randomUUID();
        UUID progressoId = UUID.randomUUID();
        UsuarioEntity usuarioConcorrente = UsuarioEntity.builder()
                .id(usuarioId)
                .nomeCompleto(request.getNomeCompleto())
                .email(request.getEmail())
                .hashSenha("hash")
                .build();
        ProgressoCadastroEntity progressoConcorrente = ProgressoCadastroEntity.builder()
                .id(progressoId)
                .usuarioId(usuarioId)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadHash(hashPayloadDoMesmoJeitoQueOServico(request))
                .etapaAtual(2)
                .build();

        when(progressoCadastroRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(progressoConcorrente));
        when(usuarioRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(usuarioRepository.save(any(UsuarioEntity.class))).thenThrow(new DataIntegrityViolationException("unique violation"));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuarioConcorrente));

        AcessoResponse response = acessoService.create(IDEMPOTENCY_KEY, request);

        assertThat(response.getToken()).isEqualTo(progressoId);
    }

    // 11. Resposta nunca expoe hash_senha (verificacao estrutural do DTO)
    @Test
    void acessoResponse_naoPossuiCampoDeHashDeSenha() {
        assertThatThrownBy(() -> AcessoResponse.class.getDeclaredField("hashSenha"))
                .isInstanceOf(NoSuchFieldException.class);
        assertThatThrownBy(() -> AcessoResponse.class.getDeclaredField("senha"))
                .isInstanceOf(NoSuchFieldException.class);
    }
}
