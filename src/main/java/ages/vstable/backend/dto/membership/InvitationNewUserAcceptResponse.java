package ages.vstable.backend.dto.membership;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** Conta e vínculo criados pelo convite. Nenhum JWT é emitido: o próximo passo é o login normal. */
@Schema(description = "Conta criada a partir do convite; o acesso começa pelo login normal")
public record InvitationNewUserAcceptResponse(
        UUID userId,
        MemberResponse membership,
        @Schema(description = "Sempre LOGIN", example = "LOGIN")
        String nextAction) {

    public static final String NEXT_ACTION_LOGIN = "LOGIN";

    @Override
    public String toString() {
        return "InvitationNewUserAcceptResponse[userId=" + userId + ", nextAction=" + nextAction + "]";
    }
}
