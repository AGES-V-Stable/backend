package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BlindPayWebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;

public interface BlindPayWebhookEventRepository extends JpaRepository<BlindPayWebhookEventEntity, String> {

    /**
     * Registra a mensagem se ainda não existir. Retorna 0 para retentativas: a
     * inserção concorrente espera o commit da primeira e então não faz nada.
     */
    @Modifying
    @Query(value = """
            INSERT INTO blindpay_webhook_events (message_id, event_type, received_at)
            VALUES (:messageId, :eventType, :receivedAt)
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String messageId, String eventType, OffsetDateTime receivedAt);
}
