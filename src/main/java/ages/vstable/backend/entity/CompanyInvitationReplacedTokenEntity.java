package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Hash de um link de convite que foi substituído por regeneração. Não guarda o token nem cópia cifrada:
 * serve para reconhecer o link antigo e para contar as gerações do convite na janela do limite.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "company_invitation_replaced_tokens")
public class CompanyInvitationReplacedTokenEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "invitation_id", nullable = false, updatable = false)
    private UUID invitationId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    /** Instante da substituição, que é também o da geração do link seguinte. */
    @Column(name = "replaced_at", nullable = false, updatable = false)
    private OffsetDateTime replacedAt;
}
