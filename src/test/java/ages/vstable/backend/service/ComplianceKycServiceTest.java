package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttempt;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttemptsResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceKycServiceTest {

    private static final String SUB_ACCOUNT_ID = "sub-1";

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private AveniaClient aveniaClient;

    @Mock
    private AveniaSubAccountProvisioningService subAccountProvisioningService;

    private ComplianceKycService service() {
        return new ComplianceKycService(aveniaKycVerificationRepository, aveniaClient, subAccountProvisioningService);
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

    private AveniaKycAttempt attempt(String id, String status, String result, boolean retryable) {
        AveniaKycAttempt attempt = new AveniaKycAttempt();
        attempt.setId(id);
        attempt.setStatus(status);
        attempt.setResult(result);
        attempt.setRetryable(retryable);
        return attempt;
    }

    private void stubSemTentativaAnterior(UUID userId) {
        when(subAccountProvisioningService.ensureSubAccountId(any())).thenReturn(SUB_ACCOUNT_ID);
        AveniaKycAttemptsResponse vazio = new AveniaKycAttemptsResponse();
        vazio.setAttempts(List.of());
        when(aveniaClient.listarTentativasKyc(SUB_ACCOUNT_ID)).thenReturn(vazio);
    }

    // Usuário não é dono do kycVerificationId --------------------------------

    @Test
    void finalizar_usuarioNaoEhDonoDoKyc_lancaForbiddenExceptionSemChamarAvenia() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID donoReal = UUID.randomUUID();
        UUID outroUsuario = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder().id(kycId).userId(donoReal).build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        assertThatThrownBy(() -> service.finalizar(kycId, outroUsuario, kycRequest()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(aveniaClient);
        verifyNoInteractions(subAccountProvisioningService);
    }

    @Test
    void finalizar_kycInexistente_retornaVazioSemChamarAvenia() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.empty());

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, UUID.randomUUID(), kycRequest());

        assertThat(resultado).isEmpty();
        verifyNoInteractions(aveniaClient);
    }

    // Já aprovado: idempotente, nunca reenvia ---------------------------------

    @Test
    void finalizar_kycJaAprovadoLocalmente_naoChamaAveniaERetornaAprovado() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).status(ComplianceStatus.APPROVED).aveniaProcessId("kyc-1").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.APPROVED);
        verifyNoInteractions(aveniaClient);
        verifyNoInteractions(subAccountProvisioningService);
        verify(aveniaKycVerificationRepository, never()).save(any());
    }

    // Sem tentativa anterior: submete uma nova e persiste o attemptId --------

    @Test
    void finalizar_semTentativaAnterior_comDocumentoELivenessConcluidos_submeteNovaTentativaNaSubconta() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123").build();

        AveniaKycResponse aveniaResponse = new AveniaKycResponse();
        aveniaResponse.setId("kyc-process-123");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        stubSemTentativaAnterior(userId);
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID)))
                .thenReturn(aveniaResponse);

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getAveniaProcessId()).isEqualTo("kyc-process-123");
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);

        ArgumentCaptor<AveniaKycVerificationEntity> captor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(aveniaKycVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getAveniaProcessId()).isEqualTo("kyc-process-123");
        assertThat(captor.getValue().getStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
    }

    @Test
    void finalizar_documentoAindaNaoConcluido_lancaUnprocessableEntityExceptionSemSubmeter() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).livenessId("liveness-123").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        stubSemTentativaAnterior(userId);

        assertThatThrownBy(() -> service.finalizar(kycId, userId, kycRequest()))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(aveniaClient, never()).finalizarKyc(any(), any(), any(), any());
    }

    @Test
    void finalizar_livenessAindaNaoConcluido_lancaUnprocessableEntityExceptionSemSubmeter() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        stubSemTentativaAnterior(userId);

        assertThatThrownBy(() -> service.finalizar(kycId, userId, kycRequest()))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(aveniaClient, never()).finalizarKyc(any(), any(), any(), any());
    }

    @Test
    void finalizar_falhaNaAvenia_propagaAveniaIntegrationExceptionSemSalvar() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        stubSemTentativaAnterior(userId);
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID)))
                .thenThrow(new AveniaIntegrationException("falha", new RuntimeException()));

        assertThatThrownBy(() -> service.finalizar(kycId, userId, kycRequest()))
                .isInstanceOf(AveniaIntegrationException.class);
        verify(aveniaKycVerificationRepository, never()).save(any());
    }

    // Tentativa existente consultada antes de submeter ------------------------

    @Test
    void finalizar_tentativaExistentePendente_naoReenviaERetornaUnderReview() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123")
                .aveniaProcessId("kyc-attempt-1").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.consultarTentativa("kyc-attempt-1", SUB_ACCOUNT_ID))
                .thenReturn(attempt("kyc-attempt-1", "PENDING", null, false));

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
        verify(aveniaClient, never()).finalizarKyc(any(), any(), any(), any());

        ArgumentCaptor<AveniaKycVerificationEntity> captor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(aveniaKycVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
    }

    @Test
    void finalizar_tentativaExistenteAprovada_marcaAprovadoLocalmenteSemReenviar() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123")
                .aveniaProcessId("kyc-attempt-1").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.consultarTentativa("kyc-attempt-1", SUB_ACCOUNT_ID))
                .thenReturn(attempt("kyc-attempt-1", "COMPLETED", "APPROVED", false));

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.APPROVED);
        verify(aveniaClient, never()).finalizarKyc(any(), any(), any(), any());
    }

    @Test
    void finalizar_tentativaExistenteRejeitadaNaoRetryable_bloqueiaSemReenviar() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123")
                .aveniaProcessId("kyc-attempt-1").build();

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.consultarTentativa("kyc-attempt-1", SUB_ACCOUNT_ID))
                .thenReturn(attempt("kyc-attempt-1", "COMPLETED", "REJECTED", false));

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.REJECTED);
        verify(aveniaClient, never()).finalizarKyc(any(), any(), any(), any());
    }

    @Test
    void finalizar_tentativaExistenteRejeitadaRetryable_submeteNovaTentativa() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123")
                .aveniaProcessId("kyc-attempt-1").build();

        AveniaKycResponse novaResposta = new AveniaKycResponse();
        novaResposta.setId("kyc-attempt-2");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.consultarTentativa("kyc-attempt-1", SUB_ACCOUNT_ID))
                .thenReturn(attempt("kyc-attempt-1", "COMPLETED", "REJECTED", true));
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID)))
                .thenReturn(novaResposta);

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getAveniaProcessId()).isEqualTo("kyc-attempt-2");
        assertThat(resultado.get().getStatus()).isEqualTo(ComplianceStatus.UNDER_REVIEW);
        verify(aveniaClient).finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID));
    }

    @Test
    void finalizar_tentativaExistenteExpirada_submeteNovaTentativa() {
        ComplianceKycService service = service();
        UUID kycId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-123").livenessId("liveness-123")
                .aveniaProcessId("kyc-attempt-1").build();

        AveniaKycResponse novaResposta = new AveniaKycResponse();
        novaResposta.setId("kyc-attempt-2");

        when(aveniaKycVerificationRepository.findById(kycId)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.consultarTentativa("kyc-attempt-1", SUB_ACCOUNT_ID))
                .thenReturn(attempt("kyc-attempt-1", "EXPIRED", null, false));
        when(aveniaClient.finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID)))
                .thenReturn(novaResposta);

        Optional<KycSubmitResponse> resultado = service.finalizar(kycId, userId, kycRequest());

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getAveniaProcessId()).isEqualTo("kyc-attempt-2");
        verify(aveniaClient).finalizarKyc(any(), eq("doc-123"), eq("liveness-123"), eq(SUB_ACCOUNT_ID));
    }
}
