package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite de membro compartilhado manualmente por link, sem expiração por tempo. O hash valida
 * o token e permanece depois do aceite ou da revogação; a cópia cifrada, que permite ao
 * proprietário copiar o link de novo, só existe enquanto o convite está pendente.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "company_invitations")
public class CompanyInvitationEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Nome indicado pelo proprietário ao convidar. */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private CompanyRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status;

    /** Começa em 1; as operações que alteram o convite devem incrementá-la a cada alteração persistida. */
    @Builder.Default
    @Column(nullable = false)
    private long version = 1;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_ciphertext", columnDefinition = "text")
    private String tokenCiphertext;

    @Column(name = "token_key_version", length = 20)
    private String tokenKeyVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Usuário que aceitou o convite; nulo enquanto não aceito. */
    @Column(name = "accepted_by")
    private UUID acceptedBy;

    /** Vínculo criado pelo aceite; nulo enquanto não aceito. */
    @Column(name = "membership_id")
    private UUID membershipId;

    /** Versão dos termos aceita junto com o convite. */
    @Column(name = "terms_version", length = 50)
    private String termsVersion;

    @Column(name = "terms_accepted_at")
    private OffsetDateTime termsAcceptedAt;
}
