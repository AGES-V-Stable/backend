package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sessão de aceite dos termos da BlindPay (fluxo hospedado). A
 * {@code idempotencyKey} enviada ao iniciar volta no webhook {@code tos.accept} e
 * identifica a empresa mesmo quando ainda não existe {@code customer_id}.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blindpay_tos_sessions")
public class BlindPayTosSessionEntity {

    public static final String STATUS_INITIATED = "INITIATED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "tos_id", length = 15)
    private String tosId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;
}
