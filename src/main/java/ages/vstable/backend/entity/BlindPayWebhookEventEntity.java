package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Webhook da BlindPay já processado, identificado pelo {@code svix-id}. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blindpay_webhook_events")
public class BlindPayWebhookEventEntity {

    @Id
    @Column(name = "message_id", nullable = false, updatable = false)
    private String messageId;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
