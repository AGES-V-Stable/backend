package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Apenas o novo perfil; nome, e-mail, empresa e identidade nao podem ser modificados. */
public record MemberRoleUpdateRequest(
        @Schema(description = "Novo perfil do membro: OPERATOR ou VIEWER")
        @NotNull(message = "Informe o novo perfil.")
        InviteRole role) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Campo nao permitido: " + field);
    }
}
