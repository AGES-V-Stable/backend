package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessStatusResponse;
import ages.vstable.backend.dto.compliance.LivenessSubmitRequest;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
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
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private AveniaClient aveniaClient;

    private ComplianceLivenessService service;

    private ComplianceLivenessService service() {
        return new ComplianceLivenessService(aveniaKycVerificationRepository, aveniaClient);
    }

    @Test
    void iniciar_kycExistente_chamaAveniaERetornaCampos() {
        service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        AveniaDocumentResponse aveniaResponse = new AveniaDocumentResponse();
        aveniaResponse.setId("liveness-123");
        aveniaResponse.setSessionId("session-456");
        aveniaResponse.setLivenessUrl("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        aveniaResponse.setValidateLivenessToken("token-789");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.iniciarLiveness()).thenReturn(aveniaResponse);

        Optional<LivenessStartResponse> resultado = service.iniciar(kycId);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getId()).isEqualTo("liveness-123");
        assertThat(resultado.get().getSessionId()).isEqualTo("session-456");
        assertThat(resultado.get().getLivenessUrl()).isEqualTo("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        assertThat(resultado.get().getValidateLivenessToken()).isEqualTo("token-789");
    }

    @Test
    void iniciar_kycInexistente_retornaVazioSemChamarAvenia() {
        service = service();
        UUID kycId = UUID.randomUUID();
        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        Optional<LivenessStartResponse> resultado = service.iniciar(kycId);

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void iniciar_falhaNaAvenia_propagaAveniaIntegrationException() {
        service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.iniciarLiveness()).thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.iniciar(kycId))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void consultarStatus_kycExistente_chamaAveniaERetornaReadyEStatus() {
        service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        AveniaDocumentStatusResponse aveniaResponse = new AveniaDocumentStatusResponse();
        AveniaDocumentStatusResponse.Document document = new AveniaDocumentStatusResponse.Document();
        document.setReady(true);
        document.setUploadStatusFront("UPLOADED");
        aveniaResponse.setDocument(document);

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.consultarStatusDocumento("liveness-123")).thenReturn(aveniaResponse);

        Optional<LivenessStatusResponse> resultado = service.consultarStatus(kycId, "liveness-123");

        assertThat(resultado).isPresent();
        assertThat(resultado.get().isReady()).isTrue();
        assertThat(resultado.get().getStatus()).isEqualTo("UPLOADED");
    }

    @Test
    void consultarStatus_kycInexistente_retornaVazioSemChamarAvenia() {
        service = service();
        UUID kycId = UUID.randomUUID();
        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        Optional<LivenessStatusResponse> resultado = service.consultarStatus(kycId, "liveness-123");

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void consultarStatus_falhaNaAvenia_propagaAveniaIntegrationException() {
        service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.consultarStatusDocumento("liveness-123"))
                .thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.consultarStatus(kycId, "liveness-123"))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void concluir_kycExistente_salvaLivenessIdERetornaTrue() {
        service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        LivenessSubmitRequest request = new LivenessSubmitRequest();
        request.setLivenessId("liveness-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        boolean resultado = service.concluir(kycId, request);

        assertThat(resultado).isTrue();

        ArgumentCaptor<AveniaKycVerificationEntity> captor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(aveniaKycVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getLivenessId()).isEqualTo("liveness-123");
    }

    @Test
    void concluir_kycInexistente_retornaFalseSemSalvar() {
        service = service();
        UUID kycId = UUID.randomUUID();
        LivenessSubmitRequest request = new LivenessSubmitRequest();
        request.setLivenessId("liveness-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        boolean resultado = service.concluir(kycId, request);

        assertThat(resultado).isFalse();
        verify(aveniaKycVerificationRepository, never()).save(any());
    }
}
