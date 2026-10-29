package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Recorte dos webhooks de compliance da BlindPay. {@code customer.new} e
 * {@code customer.update} trazem o cliente no nível raiz (ver CustomerUpdateWebhookOut);
 * {@code tos.accept} traz {@code tos_id} e a {@code idempotency_key} usada ao iniciar
 * o aceite, com {@code customer_id} opcional. Dados pessoais e documentos do corpo
 * são ignorados.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayWebhookEvent(
        String webhookEvent,
        String id,
        String customerId,
        String instanceId,
        String externalId,
        String kycStatus,
        String amlStatus,
        String tosId,
        Boolean isTosAccepted,
        UUID idempotencyKey,
        OffsetDateTime updatedAt
) {

    public static final String CUSTOMER_NEW = "customer.new";
    public static final String CUSTOMER_UPDATE = "customer.update";
    public static final String TOS_ACCEPT = "tos.accept";
    /** Eventos financeiros: {@code id} é o payin ({@code pi_}) ou o payout ({@code po_}). */
    public static final String PAYIN_NEW = "payin.new";
    public static final String PAYIN_UPDATE = "payin.update";
    public static final String PAYIN_COMPLETE = "payin.complete";
    public static final String PAYOUT_NEW = "payout.new";
    public static final String PAYOUT_UPDATE = "payout.update";
    public static final String PAYOUT_COMPLETE = "payout.complete";
}
