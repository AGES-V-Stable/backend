package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Vínculo de um usuário com a empresa")
public record MemberResponse(
        @Schema(description = "Identificador do vínculo; não é o id do usuário")
        UUID id,
        UUID userId,
        UUID companyId,
        String name,
        String email,
        CompanyRole role,
        MembershipStatus status,
        @Schema(description = "Começa em 1 e muda a cada alteração persistida do vínculo")
        long version,
        OffsetDateTime joinedAt,
        OffsetDateTime removedAt) {
}
