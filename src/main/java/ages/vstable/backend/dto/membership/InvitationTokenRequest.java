package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token do link de convite, enviado no corpo e nunca na URL. Um token ausente ou fora do formato é tratado
 * como convite inválido (404), igual a um token inexistente.
 */
public record InvitationTokenRequest(
        @Schema(description = "Token do fragmento #token= do link: 43 caracteres base64url")
        String token) {

    /** O token é um segredo: fica fora de qualquer toString acidental. */
    @Override
    public String toString() {
        return "InvitationTokenRequest[token=***]";
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Campo não permitido: " + field);
    }
}
