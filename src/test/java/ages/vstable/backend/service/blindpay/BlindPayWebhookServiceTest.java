package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.BlindPayWebhookVerifier;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.BlindPayWebhookEventRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.service.transfer.BlindPayExecutionProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlindPayWebhookServiceTest {

    static final byte[] SECRET_KEY = "blindpay-webhook-test-secret-key".getBytes(StandardCharsets.UTF_8);
    static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(SECRET_KEY);
    static final String INSTANCE_ID = "in_000000000001";
    static final String CUSTOMER_ID = "re_000000000001";

    @Mock private BlindPayWebhookEventRepository webhookEventRepository;
    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private BlindPayTosSessionRepository tosSessionRepository;
    @Mock private BlindPayExecutionProcessor executionProcessor;

    private BlindPayWebhookService service;
    private CompanyEntity company;

    @BeforeEach
    void setUp() {
        BlindPayProperties properties = new BlindPayProperties();
        properties.setInstanceId(INSTANCE_ID);
        properties.setWebhookSecret(SECRET);
        service = new BlindPayWebhookService(new BlindPayWebhookVerifier(properties), properties,
                webhookEventRepository, providerAccountRepository, tosSessionRepository,
                JsonMapper.builder().build(), executionProcessor);
        company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        when(webhookEventRepository.insertIfAbsent(anyString(), any(), any())).thenReturn(1);
    }

    @Test
    void handle_invalidSignature_isRejectedWithoutReadingOrPersisting() {
        // Arrange
        byte[] body = customerUpdate("approved", "clear", company.getId().toString(), INSTANCE_ID).getBytes();
        long now = Instant.now().getEpochSecond();

        // Act
        BlindPayWebhookService.Result result = service.handle(
                "msg_1", Long.toString(now), "v1," + Base64.getEncoder().encodeToString(new byte[32]), body);

        // Assert
        assertThat(result).isEqualTo(BlindPayWebhookService.Result.INVALID_SIGNATURE);
        verifyNoInteractions(webhookEventRepository, providerAccountRepository, tosSessionRepository);
    }

    @Test
    void handle_tamperedBody_isRejected() {
        String original = customerUpdate("verifying", null, company.getId().toString(), INSTANCE_ID);
        long now = Instant.now().getEpochSecond();
        String signature = sign("msg_1", now, original.getBytes());

        BlindPayWebhookService.Result result = service.handle("msg_1", Long.toString(now), signature,
                original.replace("verifying", "approved").getBytes());

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.INVALID_SIGNATURE);
        verifyNoInteractions(providerAccountRepository);
    }

    @Test
    void handle_timestampOutsideTolerance_isRejected() {
        byte[] body = customerUpdate("approved", "clear", company.getId().toString(), INSTANCE_ID).getBytes();
        long tenMinutesAgo = Instant.now().minusSeconds(600).getEpochSecond();

        BlindPayWebhookService.Result result = service.handle(
                "msg_1", Long.toString(tenMinutesAgo), sign("msg_1", tenMinutesAgo, body), body);

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.INVALID_SIGNATURE);
        verifyNoInteractions(providerAccountRepository);
    }

    @Test
    void handle_repeatedMessageId_isIgnoredAsDuplicate() {
        // Arrange
        when(webhookEventRepository.insertIfAbsent(eq("msg_1"), eq("customer.update"), any())).thenReturn(0);

        // Act
        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("approved", "clear", company.getId().toString(), INSTANCE_ID));

        // Assert
        assertThat(result).isEqualTo(BlindPayWebhookService.Result.DUPLICATE);
        verifyNoInteractions(providerAccountRepository);
    }

    @Test
    void handle_customerUpdate_appliesKybAndAmlStatusToProviderAccount() {
        // Arrange
        ProviderAccountEntity account = linkedAccount();

        // Act
        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("approved", "clear", company.getId().toString(), INSTANCE_ID));

        // Assert
        assertThat(result).isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        assertThat(account.getKycStatus()).isEqualTo("approved");
        assertThat(account.getAmlStatus()).isEqualTo("clear");
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.APPROVED.name());
        verify(providerAccountRepository).save(account);
    }

    @Test
    void handle_customerUpdateWithAmlHit_isNotApproved() {
        ProviderAccountEntity account = linkedAccount();

        deliver("msg_1", customerUpdate("approved", "hit", company.getId().toString(), INSTANCE_ID));

        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.AML_HIT.name());
    }

    @Test
    void handle_customerUpdateFromAnotherInstance_isIgnored() {
        ProviderAccountEntity account = linkedAccount();

        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("approved", "clear", company.getId().toString(), "in_999999999999"));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.VERIFYING.name());
        verify(providerAccountRepository, never()).save(any());
    }

    @Test
    void handle_customerUpdateWithExternalIdOfAnotherCompany_isIgnored() {
        ProviderAccountEntity account = linkedAccount();

        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("approved", "clear", UUID.randomUUID().toString(), INSTANCE_ID));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.VERIFYING.name());
    }

    @Test
    void handle_olderCustomerUpdate_doesNotOverwriteNewerStatus() {
        ProviderAccountEntity account = linkedAccount();
        account.setKycStatus("approved");
        account.setAmlStatus("clear");
        account.setStatus(BlindPayOnboardingStatus.APPROVED.name());
        account.setProviderUpdatedAt(OffsetDateTime.parse("2026-10-15T12:00:00Z"));

        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("verifying", null, company.getId().toString(), INSTANCE_ID));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.APPROVED.name());
    }

    @Test
    void handle_customerNewForUnlinkedAccount_linksCustomerByExternalId() {
        // Arrange: o POST /customers criou o cliente, mas a resposta não foi gravada.
        ProviderAccountEntity account = account(null);
        when(providerAccountRepository.findByProviderAndExternalCustomerId(IntegrationProvider.BLINDPAY, CUSTOMER_ID))
                .thenReturn(Optional.empty());
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));

        // Act
        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("verifying", null, company.getId().toString(), INSTANCE_ID)
                        .replace("customer.update", "customer.new"));

        // Assert
        assertThat(result).isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        assertThat(account.getExternalCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.VERIFYING.name());
    }

    @Test
    void handle_customerUpdateOfUnknownCustomer_isIgnored() {
        when(providerAccountRepository.findByProviderAndExternalCustomerId(any(), any())).thenReturn(Optional.empty());

        BlindPayWebhookService.Result result = deliver("msg_1",
                customerUpdate("approved", "clear", "not-a-uuid", INSTANCE_ID));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        verify(providerAccountRepository, never()).save(any());
    }

    @Test
    void handle_tosAcceptWithoutCustomerId_correlatesSessionByIdempotencyKey() {
        // Arrange
        UUID idempotencyKey = UUID.randomUUID();
        BlindPayTosSessionEntity session = session(idempotencyKey);
        ProviderAccountEntity account = account(null);
        account.setStatus(BlindPayOnboardingStatus.TOS_PENDING.name());
        account.setTosId(null);
        account.setTosAcceptedAt(null);
        when(tosSessionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(session));
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));

        // Act
        BlindPayWebhookService.Result result = deliver("msg_tos", tosAccept(idempotencyKey, null));

        // Assert
        assertThat(result).isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        assertThat(session.getStatus()).isEqualTo(BlindPayTosSessionEntity.STATUS_ACCEPTED);
        assertThat(session.getTosId()).isEqualTo("to_000000000001");
        assertThat(account.getTosId()).isEqualTo("to_000000000001");
        assertThat(account.getTosAcceptedAt()).isNotNull();
        assertThat(account.getStatus()).isEqualTo(BlindPayOnboardingStatus.TOS_ACCEPTED.name());
    }

    @Test
    void handle_tosAcceptForUnknownSession_isIgnored() {
        when(tosSessionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        BlindPayWebhookService.Result result = deliver("msg_tos", tosAccept(UUID.randomUUID(), null));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        verify(providerAccountRepository, never()).save(any());
    }

    @Test
    void handle_tosAcceptForAnotherCustomer_isIgnored() {
        UUID idempotencyKey = UUID.randomUUID();
        BlindPayTosSessionEntity session = session(idempotencyKey);
        when(tosSessionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(session));
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(CUSTOMER_ID)));

        BlindPayWebhookService.Result result = deliver("msg_tos", tosAccept(idempotencyKey, "re_999999999999"));

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        assertThat(session.getStatus()).isEqualTo(BlindPayTosSessionEntity.STATUS_INITIATED);
    }

    @Test
    void handle_unsupportedEvent_isRecordedAndIgnored() {
        BlindPayWebhookService.Result result = deliver("msg_1",
                "{\"webhook_event\":\"payout.complete\",\"id\":\"po_000000000001\"}");

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
        verify(webhookEventRepository).insertIfAbsent(eq("msg_1"), eq("payout.complete"), any());
        verifyNoInteractions(providerAccountRepository);
    }

    @Test
    void handle_validSignatureWithMalformedJson_isBadRequest() {
        assertThatThrownBy(() -> deliver("msg_1", "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed BlindPay webhook payload");
    }

    @Test
    void handle_payinComplete_isRoutedToTransferExecutionByPayinId() {
        when(executionProcessor.onPayinEvent("pi_000000000001")).thenReturn(true);

        BlindPayWebhookService.Result result = deliver("msg_payin",
                "{\"webhook_event\":\"payin.complete\",\"id\":\"pi_000000000001\",\"status\":\"completed\"}");

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        verify(executionProcessor).onPayinEvent("pi_000000000001");
        verifyNoInteractions(providerAccountRepository, tosSessionRepository);
    }

    @Test
    void handle_payoutUpdate_isRoutedToTransferExecutionByPayoutId() {
        when(executionProcessor.onPayoutEvent("po_000000000001")).thenReturn(true);

        BlindPayWebhookService.Result result = deliver("msg_payout",
                "{\"webhook_event\":\"payout.update\",\"id\":\"po_000000000001\",\"status\":\"failed\"}");

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        verify(executionProcessor).onPayoutEvent("po_000000000001");
    }

    @Test
    void handle_payoutOfUnknownTransfer_isIgnored() {
        when(executionProcessor.onPayoutEvent("po_unknown")).thenReturn(false);

        BlindPayWebhookService.Result result = deliver("msg_unknown",
                "{\"webhook_event\":\"payout.complete\",\"id\":\"po_unknown\",\"status\":\"completed\"}");

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.IGNORED);
    }

    @Test
    void handle_repeatedFinancialMessage_isNotProcessedTwice() {
        when(webhookEventRepository.insertIfAbsent(eq("msg_repeat"), any(), any())).thenReturn(0);

        BlindPayWebhookService.Result result = deliver("msg_repeat",
                "{\"webhook_event\":\"payin.complete\",\"id\":\"pi_000000000001\",\"status\":\"completed\"}");

        assertThat(result).isEqualTo(BlindPayWebhookService.Result.DUPLICATE);
        verifyNoInteractions(executionProcessor);
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private BlindPayWebhookService.Result deliver(String messageId, String body) {
        long now = Instant.now().getEpochSecond();
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return service.handle(messageId, Long.toString(now), sign(messageId, now, raw), raw);
    }

    static String sign(String messageId, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY, "HmacSHA256"));
            mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ProviderAccountEntity linkedAccount() {
        ProviderAccountEntity account = account(CUSTOMER_ID);
        when(providerAccountRepository.findByProviderAndExternalCustomerId(IntegrationProvider.BLINDPAY, CUSTOMER_ID))
                .thenReturn(Optional.of(account));
        return account;
    }

    private ProviderAccountEntity account(String customerId) {
        return ProviderAccountEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId(customerId)
                .status(BlindPayOnboardingStatus.VERIFYING.name())
                .tosId("to_000000000001")
                .tosAcceptedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private BlindPayTosSessionEntity session(UUID idempotencyKey) {
        return BlindPayTosSessionEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .requestedBy(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .status(BlindPayTosSessionEntity.STATUS_INITIATED)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    static String customerUpdate(String kycStatus, String amlStatus, String externalId, String instanceId) {
        return """
                {
                  "webhook_event": "customer.update",
                  "id": "%s",
                  "customer_id": "%s",
                  "type": "business",
                  "kyc_type": "standard",
                  "kyc_status": "%s",
                  "aml_status": %s,
                  "email": "finance@acme.test",
                  "tax_id": "11222333000181",
                  "country": "BR",
                  "external_id": "%s",
                  "instance_id": "%s",
                  "tos_id": "to_000000000001",
                  "is_tos_accepted": true,
                  "limit": {"per_transaction": 100000, "daily": 200000, "monthly": 1000000},
                  "updated_at": "2026-10-15T10:00:00Z"
                }
                """.formatted(CUSTOMER_ID, CUSTOMER_ID, kycStatus,
                amlStatus == null ? "null" : "\"" + amlStatus + "\"", externalId, instanceId);
    }

    static String tosAccept(UUID idempotencyKey, String customerId) {
        return """
                {
                  "webhook_event": "tos.accept",
                  "tos_id": "to_000000000001",
                  "idempotency_key": "%s",
                  "customer_id": %s,
                  "instance_id": "%s"
                }
                """.formatted(idempotencyKey, customerId == null ? "null" : "\"" + customerId + "\"", INSTANCE_ID);
    }
}
