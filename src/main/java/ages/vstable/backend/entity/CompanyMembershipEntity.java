package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vínculo de um usuário com uma empresa e seu perfil de acesso. A identidade de login
 * ({@link UserEntity}) é distinta da relação com a empresa; vínculos removidos permanecem
 * como histórico.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "company_memberships")
public class CompanyMembershipEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanyRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    /** Começa em 1; as operações que alteram o vínculo devem incrementá-la a cada alteração persistida. */
    @Builder.Default
    @Column(nullable = false)
    private long version = 1;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
