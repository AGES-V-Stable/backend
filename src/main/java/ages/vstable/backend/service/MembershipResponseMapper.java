package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.InvitationResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Converte vínculos e convites nos contratos HTTP, sempre com datas em UTC e sem nenhum segredo. */
public final class MembershipResponseMapper {

    private MembershipResponseMapper() {
    }

    public static MemberResponse member(CompanyMembershipEntity membership, UserEntity user) {
        return new MemberResponse(
                membership.getId(),
                membership.getUserId(),
                membership.getCompanyId(),
                user.getFullName(),
                user.getEmail(),
                membership.getRole(),
                membership.getStatus(),
                membership.getVersion(),
                utc(membership.getJoinedAt()),
                utc(membership.getRemovedAt()));
    }

    public static InvitationResponse invitation(CompanyInvitationEntity invitation, UserEntity creator) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getCompanyId(),
                invitation.getName(),
                invitation.getEmail(),
                invitation.getRole(),
                invitation.getStatus(),
                invitation.getVersion(),
                new InvitationResponse.CreatedBy(invitation.getCreatedBy(), creator.getFullName()),
                utc(invitation.getCreatedAt()),
                utc(invitation.getAcceptedAt()),
                utc(invitation.getRevokedAt()));
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
