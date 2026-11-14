package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Item da lista: um membro ativo (kind=MEMBER) ou um convite pendente (kind=INVITATION)")
public record MemberListItemResponse(
        Kind kind,
        @Schema(description = "Presente apenas quando kind=MEMBER")
        MemberResponse member,
        @Schema(description = "Presente apenas quando kind=INVITATION")
        InvitationResponse invitation) {

    public enum Kind {
        MEMBER,
        INVITATION
    }

    public static MemberListItemResponse ofMember(MemberResponse member) {
        return new MemberListItemResponse(Kind.MEMBER, member, null);
    }

    public static MemberListItemResponse ofInvitation(InvitationResponse invitation) {
        return new MemberListItemResponse(Kind.INVITATION, null, invitation);
    }
}
