package ages.vstable.backend.service;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaTermsAcceptRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTermsResponse;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AveniaTermsServiceTest {
    private static final String SUB_ACCOUNT_ID = "2eea0102-dabe-4ab6-8707-0817aa52dfe4";

    @Mock private AveniaKycVerificationRepository kycRepository;
    @Mock private AveniaSubAccountProvisioningService subAccountProvisioningService;
    @Mock private AveniaClient aveniaClient;
    @InjectMocks private AveniaTermsService service;

    private AveniaKycVerificationEntity kyc;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        kyc = AveniaKycVerificationEntity.builder()
                .id(UUID.randomUUID()).userId(userId).aveniaSubAccountId(SUB_ACCOUNT_ID).build();
    }

    @Test
    void status_listsOnlyHttpsDocumentsAndNeverTheToken() {
        givenTerms(terms(2, null));

        var status = service.status(kyc.getId(), userId).orElseThrow();

        assertThat(status.version()).isEqualTo(2);
        assertThat(status.accepted()).isFalse();
        assertThat(status.documents()).extracting("key").containsExactly("TERMS_OF_USE");
    }

    @Test
    void accept_sendsTheFreshTokenWithConsentEvidence() {
        givenTerms(terms(2, null));

        var status = service.accept(kyc.getId(), userId, 2).orElseThrow();

        ArgumentCaptor<AveniaTermsAcceptRequest> sent = ArgumentCaptor.forClass(AveniaTermsAcceptRequest.class);
        verify(aveniaClient).acceptTerms(eq(SUB_ACCOUNT_ID), sent.capture());
        assertThat(sent.getValue().bundleVersion()).isEqualTo(2);
        assertThat(sent.getValue().acceptanceToken()).isEqualTo("token-2h");
        assertThat(sent.getValue().channel()).isEqualTo("API");
        assertThat(sent.getValue().clientMetadata()).containsEntry("consentId", kyc.getId().toString());
        assertThat(status.accepted()).isTrue();
    }

    @Test
    void accept_ofAnOutdatedVersionIsRejected() {
        givenTerms(terms(3, null));

        assertThatThrownBy(() -> service.accept(kyc.getId(), userId, 2))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("TERMS_VERSION_MISMATCH");
        verify(aveniaClient, never()).acceptTerms(any(), any());
    }

    @Test
    void accept_alreadyAcceptedVersionIsNotSentAgain() {
        givenTerms(terms(2, 2));

        assertThat(service.accept(kyc.getId(), userId, 2).orElseThrow().accepted()).isTrue();
        verify(aveniaClient, never()).acceptTerms(any(), any());
    }

    @Test
    void termsOfAnotherUsersKycAreForbidden() {
        when(kycRepository.findById(kyc.getId())).thenReturn(Optional.of(kyc));

        assertThatThrownBy(() -> service.status(kyc.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        verify(aveniaClient, never()).getTerms(any());
    }

    private void givenTerms(AveniaTermsResponse terms) {
        when(kycRepository.findById(kyc.getId())).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
        when(aveniaClient.getTerms(SUB_ACCOUNT_ID)).thenReturn(terms);
    }

    private static AveniaTermsResponse terms(int current, Integer accepted) {
        return new AveniaTermsResponse(SUB_ACCOUNT_ID, new AveniaTermsResponse.Bundle("DEFAULT", current),
                List.of(new AveniaTermsResponse.Document(1, "TERMS_OF_USE", "Terms of Use",
                                "https://docs.avenia.test/terms-of-use.pdf"),
                        new AveniaTermsResponse.Document(2, "PRIVACY_POLICY", "Privacy Policy",
                                "javascript:alert(1)")),
                current, accepted, null, null, false, "token-2h");
    }
}
