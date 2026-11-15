package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycRequest;
import ages.vstable.backend.entity.BlindPayIndividualKycEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.BlindPayIndividualKycRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindPayIndividualKycServiceTest {
    @Mock BlindPayGateway gateway;
    @Mock BlindPayIndividualKycRepository repository;
    private BlindPayIndividualKycService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new BlindPayIndividualKycService(gateway, repository);
        ReflectionTestUtils.setField(service, "enabled", true);
        user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("representante@example.test")
                .build();
    }

    @Test
    void cpfRejectsInvalidCheckDigits() {
        assertThat(BlindPayIndividualKycService.validCpf("52998224725")).isTrue();
        assertThat(BlindPayIndividualKycService.validCpf("11111111111")).isFalse();
        assertThat(BlindPayIndividualKycService.validCpf("52998224726")).isFalse();
    }

    @Test
    void noRecordIsNotApproved() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        var status = service.status(user);
        assertThat(status.termsAccepted()).isFalse();
        assertThat(status.submitted()).isFalse();
    }

    @Test
    void termsAreHostedAndBoundToCurrentUser() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(gateway.initiateTermsOfService(any()))
                .thenReturn(new BlindPayInitiateTosResponse("https://app.blindpay.com/e/terms-of-service"));

        var response = service.startTerms(user);

        assertThat(response.url()).startsWith("https://app.blindpay.com/");
        ArgumentCaptor<BlindPayIndividualKycEntity> saved =
                ArgumentCaptor.forClass(BlindPayIndividualKycEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(saved.getValue().getTosSessionKey()).isNotNull();
        assertThat(saved.getValue().getTosAcceptedAt()).isNull();
    }

    @Test
    void pendingTermsSessionCanBeRegeneratedWithNewKey() {
        UUID previousKey = UUID.randomUUID();
        var entry = BlindPayIndividualKycEntity.builder()
                .userId(user.getId())
                .tosSessionKey(previousKey)
                .build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(entry));
        when(gateway.initiateTermsOfService(any()))
                .thenReturn(new BlindPayInitiateTosResponse("https://app.blindpay.com/e/terms-of-service"));

        service.startTerms(user);

        assertThat(entry.getTosSessionKey()).isNotNull().isNotEqualTo(previousKey);
        verify(repository).saveAndFlush(entry);
    }

    @Test
    void acceptedTermsCannotBeRestarted() {
        when(repository.findById(user.getId())).thenReturn(Optional.of(
                BlindPayIndividualKycEntity.builder()
                        .userId(user.getId())
                        .tosSessionKey(UUID.randomUUID())
                        .tosId("to_000000000001")
                        .tosAcceptedAt(OffsetDateTime.now())
                        .build()));

        assertThatThrownBy(() -> service.startTerms(user))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("terms already accepted");
        verifyNoInteractions(gateway);
    }

    @Test
    void rejectsSubmissionWithoutVerifiedTermsBeforeAnyUpload() {
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(
                BlindPayIndividualKycEntity.builder().userId(user.getId()).build()));
        assertThatThrownBy(() -> service.submit(user, data(), image("front"),
                null, image("selfie"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("BlindPay hosted terms not accepted");
        verifyNoInteractions(gateway);
    }

    @Test
    void standardKycUsesAuthenticatedEmailAndSeparateCustomerAccount() throws Exception {
        BlindPayIndividualKycEntity entry = BlindPayIndividualKycEntity.builder()
                .userId(user.getId())
                .tosId("to_000000000001")
                .tosAcceptedAt(OffsetDateTime.now())
                .build();
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(entry));
        when(gateway.uploadFile(any(), anyString(), anyString(), any()))
                .thenReturn(new BlindPayUploadResponse("https://files.blindpay.com/document.jpg"));
        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse("re_000000000001", null));

        var response = service.submit(user, data(), image("front"), null, image("selfie"), null);

        ArgumentCaptor<BlindPayCreateCustomerRequest> captor =
                ArgumentCaptor.forClass(BlindPayCreateCustomerRequest.class);
        verify(gateway).createCustomer(captor.capture(), anyString());
        var sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(BlindPayApi.Customer.Type.individual);
        assertThat(sent.kycType()).isEqualTo(BlindPayApi.Customer.KycType.standard);
        assertThat(sent.email()).isEqualTo(user.getEmail());
        assertThat(sent.externalId()).isEqualTo("user:" + user.getId());
        assertThat(sent.selfieFile()).startsWith("https://files.blindpay.com/");
        assertThat(response.submitted()).isTrue();
        assertThat(response.kycStatus()).isEqualTo("verifying");
        assertThat(entry.getCustomerId()).isEqualTo("re_000000000001");
    }

    @Test
    void dateOfBirthIsSentAsDateTimeLikeBlindPayOpenApi() {
        var request = BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email("representante@example.test")
                .country("BR")
                .dateOfBirth(LocalDate.of(1994, 5, 12))
                .build();

        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(request);

        assertThat(json).contains("\"date_of_birth\":\"1994-05-12T00:00:00Z\"");
    }

    @Test
    void verifiedWebhooksCanConfirmTermsAndUpdateIndividualKyc() {
        UUID key = UUID.randomUUID();
        var entry = BlindPayIndividualKycEntity.builder()
                .userId(user.getId())
                .tosSessionKey(key)
                .build();
        when(repository.findByTosSessionKey(key)).thenReturn(Optional.of(entry));
        assertThat(service.acceptTerms(key, "to_000000000001", OffsetDateTime.now())).isTrue();
        assertThat(entry.getTosId()).isEqualTo("to_000000000001");

        when(repository.findByCustomerId("re_000000000001")).thenReturn(Optional.of(entry));
        assertThat(service.updateFromWebhook("re_000000000001", "user:" + user.getId(),
                "approved", "clear", OffsetDateTime.now(), OffsetDateTime.now())).isTrue();
        assertThat(entry.getKycStatus()).isEqualTo("approved");
    }

    private static BlindPayIndividualKycRequest data() {
        return new BlindPayIndividualKycRequest(
                "Joana", "Silva", LocalDate.of(1994, 5, 12), "52998224725",
                "+5551999999999", "Rua Exemplo 100", null, "Porto Alegre", "RS",
                "BR", "90000000", "BR", BlindPayApi.Customer.IdDocType.PASSPORT, null);
    }

    private static MockMultipartFile image(String name) {
        return new MockMultipartFile(name, name + ".jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2});
    }
}
