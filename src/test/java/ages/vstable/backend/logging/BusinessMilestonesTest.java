package ages.vstable.backend.logging;

import ages.vstable.backend.dto.compliance.DocumentSubmitRequest;
import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.service.AveniaSubAccountProvisioningService;
import ages.vstable.backend.service.ComplianceDocumentService;
import ages.vstable.backend.service.ComplianceKycService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessMilestonesTest {
    private final AveniaKycVerificationRepository repository = mock(AveniaKycVerificationRepository.class);
    private final AveniaClient client = mock(AveniaClient.class);
    private final AveniaSubAccountProvisioningService provisioning = mock(AveniaSubAccountProvisioningService.class);
    private final ComplianceDocumentService documents = new ComplianceDocumentService(repository, client, provisioning);
    private final UUID userId = UUID.randomUUID();
    private final UUID verificationId = UUID.randomUUID();

    @Test
    void successfulDocumentSubmissionLogsOutcomeWithoutDocumentOrUserData() {
        var kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(userId);
        when(repository.findById(verificationId)).thenReturn(Optional.of(kyc));
        var request = new DocumentSubmitRequest();
        request.setDocumentoId("synthetic-private-document");
        try (var capture = new LogCapture(OperationalLog.class)) {
            assertThat(documents.concluir(verificationId, userId, request)).isTrue();
            verify(repository).save(kyc);
            assertThat(capture.events()).hasSize(1);
            var fields = LogCapture.fields(capture.events().getFirst());
            assertThat(fields).containsEntry("event", "document.submission.completed").containsEntry("outcome", "success");
            assertThat(fields.toString()).doesNotContain(userId.toString(), verificationId.toString(), request.getDocumentoId());
        }
    }

    @Test
    void missingComplianceRecordIsUnavailableRatherThanSuccessful() {
        when(repository.findById(verificationId)).thenReturn(Optional.empty());
        try (var capture = new LogCapture(OperationalLog.class)) {
            assertThat(documents.concluir(verificationId, userId, new DocumentSubmitRequest())).isFalse();
            assertThat(LogCapture.fields(capture.events().getFirst())).containsEntry("outcome", "unavailable");
            verify(repository, never()).save(any());
        }
    }

    @Test
    void propagatingPersistenceFailureDoesNotEmitASuccessMilestone() {
        var kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(userId);
        when(repository.findById(verificationId)).thenReturn(Optional.of(kyc));
        when(repository.save(any())).thenThrow(new IllegalStateException("synthetic-private-error"));
        try (var capture = new LogCapture(OperationalLog.class)) {
            assertThatThrownBy(() -> documents.concluir(verificationId, userId, new DocumentSubmitRequest()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void idempotentKycSubmissionReportsKnownApprovedStateWithoutResubmission() {
        var kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(ComplianceStatus.APPROVED);
        when(repository.findById(verificationId)).thenReturn(Optional.of(kyc));
        var service = new ComplianceKycService(repository, client, provisioning);
        try (var capture = new LogCapture(OperationalLog.class)) {
            assertThat(service.finalizar(verificationId, userId, new KycSubmitRequest())).isPresent();
            assertThat(LogCapture.fields(capture.events().getFirst()))
                    .containsEntry("event", "kyc.submission.completed").containsEntry("outcome", "approved");
            verifyNoInteractions(client, provisioning);
        }
    }
}
