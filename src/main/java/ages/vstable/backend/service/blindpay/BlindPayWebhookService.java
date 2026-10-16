package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.BlindPayWebhookVerifier;
import ages.vstable.backend.external.blindpay.dto.BlindPayWebhookEvent;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.BlindPayWebhookEventRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Processa os webhooks de compliance da BlindPay ({@code customer.new},
 * {@code customer.update} e {@code tos.accept}). A assinatura Svix é conferida sobre o
 * corpo bruto antes de qualquer leitura; mensagens repetidas (mesmo {@code svix-id})
 * são ignoradas. Registro e processamento estão na mesma transação: se o
 * processamento falhar, a retentativa da BlindPay é processada de novo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindPayWebhookService {

    private static final String TOS_ID_PREFIX = "to_";
    private static final int EVENT_TYPE_MAX_LENGTH = 50;

    private final BlindPayWebhookVerifier webhookVerifier;
    private final BlindPayProperties properties;
    private final BlindPayWebhookEventRepository webhookEventRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final BlindPayTosSessionRepository tosSessionRepository;
    private final ObjectMapper objectMapper;

    public enum Result {
        PROCESSED,
        DUPLICATE,
        IGNORED,
        INVALID_SIGNATURE
    }

    @Transactional
    public Result handle(String messageId, String timestamp, String signature, byte[] rawBody) {
        if (!webhookVerifier.isValid(messageId, timestamp, signature, rawBody)) {
            log.warn("Rejected BlindPay webhook with an invalid signature or timestamp");
            return Result.INVALID_SIGNATURE;
        }

        BlindPayWebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, BlindPayWebhookEvent.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Malformed BlindPay webhook payload");
        }
        String eventType = event == null ? null : event.webhookEvent();
        OffsetDateTime now = now();
        if (webhookEventRepository.insertIfAbsent(messageId, truncate(eventType), now) == 0) {
            return Result.DUPLICATE;
        }
        if (event == null || eventType == null) {
            return Result.IGNORED;
        }
        if (event.instanceId() != null && !event.instanceId().equals(properties.getInstanceId())) {
            log.warn("Ignored BlindPay webhook from another instance: messageId={}, event={}", messageId, eventType);
            return Result.IGNORED;
        }

        Result result = switch (eventType) {
            case BlindPayWebhookEvent.CUSTOMER_NEW, BlindPayWebhookEvent.CUSTOMER_UPDATE -> customerChanged(event, now);
            case BlindPayWebhookEvent.TOS_ACCEPT -> termsAccepted(event, now);
            default -> Result.IGNORED;
        };
        if (result == Result.IGNORED) {
            log.info("Ignored BlindPay webhook: messageId={}, event={}", messageId, eventType);
        }
        return result;
    }

    private Result customerChanged(BlindPayWebhookEvent event, OffsetDateTime now) {
        String customerId = event.id() != null ? event.id() : event.customerId();
        if (customerId == null) {
            return Result.IGNORED;
        }
        Optional<ProviderAccountEntity> found = providerAccountRepository
                .findByProviderAndExternalCustomerId(IntegrationProvider.BLINDPAY, customerId)
                .or(() -> unlinkedAccount(event.externalId()));
        if (found.isEmpty()) {
            return Result.IGNORED;
        }

        ProviderAccountEntity account = found.get();
        if (account.getExternalCustomerId() == null) {
            // O cliente foi criado, mas a resposta do POST se perdeu antes de ser gravada.
            account.setExternalCustomerId(customerId);
        }
        if (!BlindPayAccountState.belongsTo(
                account, customerId, event.externalId(), event.instanceId(), properties.getInstanceId())) {
            log.warn("Ignored BlindPay customer webhook that does not match the company: account={}",
                    account.getId());
            return Result.IGNORED;
        }
        if (!BlindPayAccountState.apply(account, event.kycStatus(), event.amlStatus(),
                event.isTosAccepted(), event.tosId(), event.updatedAt(), now)) {
            return Result.IGNORED;
        }
        providerAccountRepository.save(account);
        return Result.PROCESSED;
    }

    /** Conta da empresa indicada pelo {@code external_id} que ainda não tem cliente vinculado. */
    private Optional<ProviderAccountEntity> unlinkedAccount(String externalId) {
        UUID companyId;
        try {
            companyId = externalId == null ? null : UUID.fromString(externalId);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (companyId == null) {
            return Optional.empty();
        }
        return providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY)
                .filter(account -> account.getExternalCustomerId() == null);
    }

    private Result termsAccepted(BlindPayWebhookEvent event, OffsetDateTime now) {
        String tosId = event.tosId() != null ? event.tosId()
                : event.id() != null && event.id().startsWith(TOS_ID_PREFIX) ? event.id() : null;
        if (event.idempotencyKey() == null || tosId == null) {
            return Result.IGNORED;
        }
        BlindPayTosSessionEntity session = tosSessionRepository.findByIdempotencyKey(event.idempotencyKey())
                .orElse(null);
        if (session == null || BlindPayTosSessionEntity.STATUS_ACCEPTED.equals(session.getStatus())) {
            return Result.IGNORED;
        }
        ProviderAccountEntity account = providerAccountRepository
                .findByCompanyIdAndProvider(session.getCompany().getId(), IntegrationProvider.BLINDPAY)
                .orElse(null);
        if (account == null) {
            return Result.IGNORED;
        }
        if (event.customerId() != null && account.getExternalCustomerId() != null
                && !event.customerId().equals(account.getExternalCustomerId())) {
            log.warn("Ignored BlindPay terms acceptance for another customer: account={}", account.getId());
            return Result.IGNORED;
        }

        session.setStatus(BlindPayTosSessionEntity.STATUS_ACCEPTED);
        session.setTosId(tosId);
        session.setAcceptedAt(now);
        tosSessionRepository.save(session);

        if (account.getExternalCustomerId() == null || account.getTosId() == null) {
            account.setTosId(tosId);
        }
        account.setTosAcceptedAt(now);
        account.setStatus(account.getExternalCustomerId() == null
                ? BlindPayOnboardingStatus.TOS_ACCEPTED.name()
                : BlindPayOnboardingStatus.fromProvider(account.getKycStatus(), account.getAmlStatus(), true).name());
        account.setUpdatedAt(now);
        providerAccountRepository.save(account);
        return Result.PROCESSED;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= EVENT_TYPE_MAX_LENGTH ? value : value.substring(0, EVENT_TYPE_MAX_LENGTH);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
