package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.DocumentSubmitRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartRequest;
import ages.vstable.backend.dto.compliance.DocumentUploadStartResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
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
class ComplianceDocumentoServiceTest {

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private AveniaClient aveniaClient;

    private ComplianceDocumentoService service() {
        return new ComplianceDocumentoService(aveniaKycVerificationRepository, aveniaClient);
    }

    private DocumentUploadStartRequest requestPara(String documentType, boolean doubleSided) {
        DocumentUploadStartRequest request = new DocumentUploadStartRequest();
        request.setDocumentType(documentType);
        request.setDoubleSided(doubleSided);
        return request;
    }

    @Test
    void iniciar_kycExistente_chamaAveniaERetornaCampos() {
        ComplianceDocumentoService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        AveniaDocumentUploadResponse aveniaResponse = new AveniaDocumentUploadResponse();
        aveniaResponse.setId("doc-123");
        aveniaResponse.setUploadUrlFront("https://s3/front");
        aveniaResponse.setUploadUrlBack("https://s3/back");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.iniciarDocumento("ID", true)).thenReturn(aveniaResponse);

        Optional<DocumentUploadStartResponse> resultado = service.iniciar(kycId, requestPara("ID", true));

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getId()).isEqualTo("doc-123");
        assertThat(resultado.get().getUploadUrlFront()).isEqualTo("https://s3/front");
        assertThat(resultado.get().getUploadUrlBack()).isEqualTo("https://s3/back");
    }

    @Test
    void iniciar_kycInexistente_retornaVazioSemChamarAvenia() {
        ComplianceDocumentoService service = service();
        UUID kycId = UUID.randomUUID();
        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        Optional<DocumentUploadStartResponse> resultado = service.iniciar(kycId, requestPara("ID", true));

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    @Test
    void iniciar_falhaNaAvenia_propagaAveniaIntegrationException() {
        ComplianceDocumentoService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(aveniaClient.iniciarDocumento("PASSPORT", false))
                .thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.iniciar(kycId, requestPara("PASSPORT", false)))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void concluir_kycExistente_salvaDocumentoIdERetornaTrue() {
        ComplianceDocumentoService service = service();
        UUID kycId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).build();

        DocumentSubmitRequest request = new DocumentSubmitRequest();
        request.setDocumentoId("doc-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        boolean resultado = service.concluir(kycId, request);

        assertThat(resultado).isTrue();

        ArgumentCaptor<AveniaKycVerificationEntity> captor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(aveniaKycVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo("doc-123");
    }

    @Test
    void concluir_kycInexistente_retornaFalseSemSalvar() {
        ComplianceDocumentoService service = service();
        UUID kycId = UUID.randomUUID();
        DocumentSubmitRequest request = new DocumentSubmitRequest();
        request.setDocumentoId("doc-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        boolean resultado = service.concluir(kycId, request);

        assertThat(resultado).isFalse();
        verify(aveniaKycVerificationRepository, never()).save(any());
    }
}
