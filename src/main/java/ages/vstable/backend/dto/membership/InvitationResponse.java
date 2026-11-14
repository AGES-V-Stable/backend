package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Convite de membro; nunca inclui o token, o link, o hash nem a cópia cifrada")
public record InvitationResponse(
        @Schema(description = "Identificador do convite; não é o id do vínculo nem do usuário")
        UUID id,
        UUID companyId,
        @Schema(description = "Nome indicado pelo proprietário ao convidar")
        String name,
        String email,
        @Schema(description = "OPERATOR ou VIEWER")
        CompanyRole role,
        InvitationStatus status,
        @Schema(description = "Começa em 1 e muda a cada alteração persistida do convite")
        long version,
        CreatedBy createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime revokedAt) {

    public record CreatedBy(UUID id, String name) {
    }
}
