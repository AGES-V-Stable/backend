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
    @Mock ages.vstable.backend.repository.ProviderAccountRepository providerAccounts;
    private BlindPayIndividualKycService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new BlindPayIndividualKycService(gateway, repository, providerAccounts);
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
    void manualStatusCheckFetchesProviderAndUpdatesLinkedAccount() {
        var entry = BlindPayIndividualKycEntity.builder().userId(user.getId()).customerId("re_customer")
                .tosAcceptedAt(OffsetDateTime.now()).kycStatus("verifying").build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(entry));
        var remote = mock(BlindPayCustomerResponse.class);
        when(remote.id()).thenReturn("re_customer");
        when(remote.externalId()).thenReturn("user:" + user.getId());
        when(remote.kycStatus()).thenReturn("approved");
        when(remote.amlStatus()).thenReturn("clear");
        when(gateway.getCustomer("re_customer")).thenReturn(remote);
        var account = ages.vstable.backend.entity.ProviderAccountEntity.builder()
                .externalCustomerId("re_customer").tosAcceptedAt(OffsetDateTime.now()).status("VERIFYING").build();
        when(providerAccounts.findByProviderAndExternalCustomerId(
                ages.vstable.backend.entity.enums.IntegrationProvider.BLINDPAY, "re_customer"))
                .thenReturn(Optional.of(account));

        var status = service.status(user);

        assertThat(status.kycStatus()).isEqualTo("approved");
        assertThat(status.amlStatus()).isEqualTo("clear");
        assertThat(account.getStatus()).isEqualTo("APPROVED");
        verify(repository).save(entry);
        verify(providerAccounts).save(account);
    }

    @Test
    void manualStatusFailureDoesNotSilentlyReturnOldApproval() {
        var entry = BlindPayIndividualKycEntity.builder().userId(user.getId()).customerId("re_customer")
                .kycStatus("approved").amlStatus("clear").build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(entry));
        var failure = ages.vstable.backend.exception.BlindPayIntegrationException.communication("Unavailable", null);
        when(gateway.getCustomer("re_customer")).thenThrow(failure);

        assertThatThrownBy(() -> service.status(user)).isSameAs(failure);
        verify(repository, never()).save(any());
    }

    @Test
    void manualStatusRejectsAnotherCustomersResponse() {
        var entry = BlindPayIndividualKycEntity.builder().userId(user.getId()).customerId("re_customer").build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(entry));
        var remote = mock(BlindPayCustomerResponse.class);
        when(remote.id()).thenReturn("re_other");
        when(gateway.getCustomer("re_customer")).thenReturn(remote);

        assertThatThrownBy(() -> service.status(user))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void termsRedirectIsBoundToTheLatestSession() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redirectUrl", "http://localhost/kyc");
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(gateway.initiateTermsOfService(any()))
                .thenReturn(new BlindPayInitiateTosResponse("https://app.blindpay.com/e/terms-of-service"));

        service.startTerms(user);

        var saved = ArgumentCaptor.forClass(BlindPayIndividualKycEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        var request = ArgumentCaptor.forClass(BlindPayInitiateTosRequest.class);
        verify(gateway).initiateTermsOfService(request.capture());
        assertThat(request.getValue().redirectUrl()).isEqualTo("http://localhost/kyc?state=" + saved.getValue().getTosSessionKey());
    }

    @Test
    void termsCanBeConfirmedFromHostedRedirectWithoutAWebhook() {
        UUID state = UUID.randomUUID();
        var entry = BlindPayIndividualKycEntity.builder().userId(user.getId()).tosSessionKey(state).build();
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(entry));

        var result = service.confirmTerms(user, state, "to_000000000001");

        assertThat(result.termsAccepted()).isTrue();
        assertThat(entry.getTosId()).isEqualTo("to_000000000001");
        assertThat(entry.getTosAcceptedAt()).isNotNull();
        verify(repository).save(entry);
        verifyNoInteractions(gateway);
    }

    @Test
    void termsReturnCannotBeAttachedToAnotherSession() {
        var entry = BlindPayIndividualKycEntity.builder().userId(user.getId()).tosSessionKey(UUID.randomUUID()).build();
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.confirmTerms(user, UUID.randomUUID(), "to_000000000001"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("does not match");
        verify(repository, never()).save(any());
        verifyNoInteractions(gateway);
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
    void rejectedKycCanRestartAndIgnoresLateWebhooksOfTheOldCustomer() {
        var entry = BlindPayIndividualKycEntity.builder()
                .userId(user.getId())
                .customerId("re_000000000001")
                .tosId("to_000000000001")
                .tosAcceptedAt(OffsetDateTime.now())
                .kycStatus("rejected")
                .build();
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(entry));

        var status = service.restart(user);

        assertThat(status.submitted()).isFalse();
        assertThat(status.termsAccepted()).isFalse();
        assertThat(entry.getCustomerId()).isNull();
        assertThat(entry.getTosId()).isNull();
        assertThat(entry.getSupersededCustomerId()).isEqualTo("re_000000000001");

        // Webhook atrasado do cliente recusado não pode voltar a ser associado.
        when(repository.findByCustomerId("re_000000000001")).thenReturn(Optional.empty());
        when(repository.findById(user.getId())).thenReturn(Optional.of(entry));
        assertThat(service.updateFromWebhook("re_000000000001", "user:" + user.getId(),
                "rejected", null, OffsetDateTime.now(), OffsetDateTime.now())).isFalse();
        assertThat(entry.getCustomerId()).isNull();
    }

    @Test
    void onlyRejectedKycCanRestart() {
        when(repository.findForUpdate(user.getId())).thenReturn(Optional.of(
                BlindPayIndividualKycEntity.builder().userId(user.getId())
                        .customerId("re_000000000001").kycStatus("verifying").build()));

        assertThatThrownBy(() -> service.restart(user))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only a rejected KYC");
    }

    @Test
    void rfiIsReadAndAnsweredForTheIndividualCustomer() {
        when(repository.findById(user.getId())).thenReturn(Optional.of(
                BlindPayIndividualKycEntity.builder().userId(user.getId())
                        .customerId("re_000000000001").kycStatus("compliance_request").build()));
        var field = new BlindPayRfiResponse.Field("occupation", "Ocupação", null, true, null, false, null);
        var rfi = new BlindPayRfiResponse("rfi_1", "re_000000000001", null, "pending",
                java.util.List.of(new BlindPayRfiResponse.Section("Perfil", null, null, java.util.List.of(field))),
                null, null, null);
        when(gateway.getCustomerRfi("re_000000000001")).thenReturn(rfi);
        when(gateway.submitCustomerRfi(eq("re_000000000001"), any(), startsWith("individual-rfi:rfi_1:")))
                .thenReturn(new BlindPayRfiSubmitResponse(true, null));

        var remote = mock(BlindPayCustomerResponse.class);
        when(remote.id()).thenReturn("re_000000000001");
        when(remote.externalId()).thenReturn("user:" + user.getId());
        when(gateway.getCustomer("re_000000000001")).thenReturn(remote);
        var details = service.rfi(user);
        service.submitRfi(user, java.util.Map.of("occupation", "Engenheira"));

        assertThat(details.open()).isTrue();
        assertThat(details.sections().getFirst().fields().getFirst().key()).isEqualTo("occupation");
        verify(gateway).submitCustomerRfi(eq("re_000000000001"),
                eq(java.util.Map.of("occupation", "Engenheira")), anyString());
        assertThatThrownBy(() -> service.submitRfi(user, java.util.Map.of("unknown", "x")))
                .isInstanceOf(IllegalArgumentException.class);
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
