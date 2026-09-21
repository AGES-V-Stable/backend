package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
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
class ComplianceKycServiceTest {

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private AveniaClient aveniaClient;

    private ComplianceKycService service() {
        return new ComplianceKycService(aveniaKycVerificationRepository, aveniaClient);
    }

    private KycSubmitRequest kycRequest() {
        KycSubmitRequest request = new KycSubmitRequest();
        request.setFullName("Maria da Silva");
        request.setDateOfBirth("1990-05-20");
        request.setTaxIdNumber("52998224725");
        request.setEmail("maria@empresa.com");
        request.setPhone("11987654321");
        request.setCountry("Brasil");
        request.setState("SP");
        request.setCity("São Paulo");
        request.setZipCode("90000000");
        request.setStreetAddress("Rua Teste, 100");
        return request;
    }

    @Test
    void finalizar_kycComDocumentoELivenessConcluidos_chamaAveniaESalvaProcessId() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId)
                .documentId("doc-123")
                .livenessId("liveness-123")
                .build();

        AveniaKycResponse aveniaResponse = new AveniaKycResponse();
        aveniaResponse.setId("kyc-process-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123"))).thenReturn(aveniaResponse);

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getAveniaProcessId()).isEqualTo("kyc-process-123");

        ArgumentCaptor<AveniaKycVerificationEntity> captor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(aveniaKycVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getAveniaProcessId()).isEqualTo("kyc-process-123");
    }

    @Test
    void finalizar_kycInexistente_retornaVazioSemChamarAvenia() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, kycRequest());

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void finalizar_documentoAindaNaoConcluido_lancaUnprocessableEntityException() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId)
                .livenessId("liveness-123")
                .build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        assertThatThrownBy(() -> service.finalizar(kycId, kycRequest()))
                .isInstanceOf(UnprocessableEntityException.class);
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void finalizar_livenessAindaNaoConcluido_lancaUnprocessableEntityException() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId)
                .documentId("doc-123")
                .build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        assertThatThrownBy(() -> service.finalizar(kycId, kycRequest()))
                .isInstanceOf(UnprocessableEntityException.class);
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void finalizar_falhaNaAvenia_propagaAveniaIntegrationExceptionSemSalvar() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId)
                .documentId("doc-123")
                .livenessId("liveness-123")
                .build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123")))
                .thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.finalizar(kycId, kycRequest()))
                .isInstanceOf(AveniaIntegrationException.class);
        verify(aveniaKycVerificationRepository, never()).save(any());
    }
}
