package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessSubmitRequest;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceLivenessServiceTest {

    @Mock
    private ProgressoCadastroRepository progressoCadastroRepository;

    @Mock
    private AveniaClient aveniaClient;

    private ComplianceLivenessService service;

    private ComplianceLivenessService service() {
        return new ComplianceLivenessService(progressoCadastroRepository, aveniaClient);
    }

    @Test
    void iniciar_progressoExistente_chamaAveniaERetornaCampos() {
        service = service();
        UUID progressoId = UUID.randomUUID();
        ProgressoCadastroEntity progresso = ProgressoCadastroEntity.builder().id(progressoId).build();

        AveniaDocumentResponse aveniaResponse = new AveniaDocumentResponse();
        aveniaResponse.setId("liveness-123");
        aveniaResponse.setSessionId("session-456");
        aveniaResponse.setLivenessUrl("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        aveniaResponse.setValidateLivenessToken("token-789");

        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.of(progresso));
        when(aveniaClient.iniciarLiveness()).thenReturn(aveniaResponse);

        Optional<LivenessStartResponse> resultado = service.iniciar(progressoId);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getId()).isEqualTo("liveness-123");
        assertThat(resultado.get().getSessionId()).isEqualTo("session-456");
        assertThat(resultado.get().getLivenessUrl()).isEqualTo("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        assertThat(resultado.get().getValidateLivenessToken()).isEqualTo("token-789");
    }

    @Test
    void iniciar_progressoInexistente_retornaVazioSemChamarAvenia() {
        service = service();
        UUID progressoId = UUID.randomUUID();
        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.empty());

        Optional<LivenessStartResponse> resultado = service.iniciar(progressoId);

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void iniciar_falhaNaAvenia_propagaAveniaIntegrationException() {
        service = service();
        UUID progressoId = UUID.randomUUID();
        ProgressoCadastroEntity progresso = ProgressoCadastroEntity.builder().id(progressoId).build();

        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.of(progresso));
        when(aveniaClient.iniciarLiveness()).thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.iniciar(progressoId))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void concluir_progressoExistente_salvaLivenessIdERetornaTrue() {
        service = service();
        UUID progressoId = UUID.randomUUID();
        ProgressoCadastroEntity progresso = ProgressoCadastroEntity.builder().id(progressoId).build();

        LivenessSubmitRequest request = new LivenessSubmitRequest();
        request.setLivenessId("liveness-123");

        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.of(progresso));

        boolean resultado = service.concluir(progressoId, request);

        assertThat(resultado).isTrue();

        ArgumentCaptor<ProgressoCadastroEntity> captor = ArgumentCaptor.forClass(ProgressoCadastroEntity.class);
        verify(progressoCadastroRepository).save(captor.capture());
        assertThat(captor.getValue().getLivenessId()).isEqualTo("liveness-123");
    }

    @Test
    void concluir_progressoInexistente_retornaFalseSemSalvar() {
        service = service();
        UUID progressoId = UUID.randomUUID();
        LivenessSubmitRequest request = new LivenessSubmitRequest();
        request.setLivenessId("liveness-123");

        when(progressoCadastroRepository.findById(progressoId)).thenReturn(Optional.empty());

        boolean resultado = service.concluir(progressoId, request);

        assertThat(resultado).isFalse();
        verify(progressoCadastroRepository, never()).save(any());
    }
}
