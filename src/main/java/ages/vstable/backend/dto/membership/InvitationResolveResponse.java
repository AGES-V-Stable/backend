package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Dados do convite pendente necessários à tela de aceite. Não traz id de quem convidou, contagem de membros,
 * situação financeira nem dados de provedores.
 */
@Schema(description = "Convite pendente consultado pelo token, sem consumi-lo")
public record InvitationResolveResponse(
        UUID invitationId,
        Company company,
        InvitedBy invitedBy,
        Recipient recipient,
        CompanyRole role,
        @Schema(description = "REGISTER se o e-mail ainda não tem conta; LOGIN se já tem")
        InvitationAcceptanceMode acceptanceMode,
        Terms terms) {

    public record Company(UUID id, String name) {
    }

    public record InvitedBy(String name) {
    }

    public record Recipient(String name, String email) {
    }

    public record Terms(String version, String termsUrl, String privacyUrl) {
    }

    /** Nomes e e-mail são dados pessoais: ficam fora de qualquer toString acidental. */
    @Override
    public String toString() {
        return "InvitationResolveResponse[invitationId=" + invitationId + ", role=" + role
                + ", acceptanceMode=" + acceptanceMode + "]";
    }
}
