package ages.vstable.backend.dto.membership;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Convite com o link copiável; o link é um segredo e só é devolvido ao proprietário")
public record GeneratedInvitationResponse(
        InvitationResponse invitation,
        @Schema(description = "Link vigente do convite, montado com a origem pública do frontend",
                example = "https://frontend.example/convites/empresa#token=EXEMPLO_DE_TOKEN_OPACO")
        String inviteUrl) {

    /** O link é um segredo e o convite traz nome e e-mail: nada disso entra em log por um toString acidental. */
    @Override
    public String toString() {
        return "GeneratedInvitationResponse[invitationId=" + (invitation == null ? null : invitation.id())
                + ", inviteUrl=***]";
    }
}
