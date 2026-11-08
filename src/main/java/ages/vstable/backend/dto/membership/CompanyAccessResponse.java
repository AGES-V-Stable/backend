package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Acesso do usuário autenticado à empresa, calculado a partir do vínculo atual")
public record CompanyAccessResponse(
        @Schema(description = "Identificador do vínculo; não é o id do usuário")
        UUID membershipId,
        UUID companyId,
        CompanyRole role,
        MembershipStatus status,
        @Schema(description = "Capacidades concedidas pelo perfil; vínculo removido só consulta o próprio acesso")
        List<CompanyPermission> permissions,
        @Schema(description = "Começa em 1 e muda a cada alteração persistida do vínculo")
        long version) {
}
