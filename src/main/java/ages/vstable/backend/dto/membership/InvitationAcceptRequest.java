package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceite do convite por quem já tem conta e está autenticado. E-mail, empresa e perfil vêm do convite; qualquer
 * outro campo é recusado (400), para que ninguém troque identidade ou permissões pelo corpo.
 */
public record InvitationAcceptRequest(
        @Schema(description = "Token do fragmento #token= do link")
        String token,
        @Schema(description = "Precisa ser true")
        Boolean termsAccepted,
        @Schema(description = "Versão dos termos exibida na consulta do convite")
        @NotBlank(message = "Informe a versão dos termos.")
        @Size(max = 50, message = "A versão dos termos deve ter até 50 caracteres.")
        String termsVersion) {

    @Override
    public String toString() {
        return "InvitationAcceptRequest[token=***, termsAccepted=" + termsAccepted + "]";
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Campo não permitido: " + field);
    }
}
