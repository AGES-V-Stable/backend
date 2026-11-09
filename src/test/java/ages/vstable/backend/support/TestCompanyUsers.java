package ages.vstable.backend.support;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Usuários de empresa com o vínculo atual carregado, como o filtro JWT os entrega aos controllers. */
public final class TestCompanyUsers {

    private TestCompanyUsers() {
    }

    /** Usuário com vínculo ativo na empresa, no perfil informado. */
    public static UserEntity member(UUID companyId, CompanyRole role) {
        return withMembership(newUser(companyId), companyId, role, MembershipStatus.ACTIVE);
    }

    /** Usuário cujo vínculo na empresa foi removido. */
    public static UserEntity removedMember(UUID companyId, CompanyRole role) {
        return withMembership(newUser(companyId), companyId, role, MembershipStatus.REMOVED);
    }

    public static UserEntity withMembership(UserEntity user, UUID companyId, CompanyRole role,
                                            MembershipStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setMembership(CompanyMembershipEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .userId(user.getId())
                .role(role)
                .status(status)
                .joinedAt(now)
                .removedAt(status == MembershipStatus.REMOVED ? now : null)
                .updatedAt(now)
                .build());
        return user;
    }

    private static UserEntity newUser(UUID companyId) {
        return UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
    }
}
