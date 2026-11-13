package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de quem ainda não tem conta, a partir do convite. Nome, e-mail, empresa e perfil vêm do convite;
 * qualquer outro campo é recusado (400). A política de senha é validada no serviço (422).
 */
public record InvitationNewUserAcceptRequest(
        @Schema(description = "Token do fragmento #token= do link")
        String token,
        @Schema(description = "Mínimo de 8 caracteres, um dígito e um caractere especial; até 72 bytes em UTF-8")
        @NotNull(message = "Informe a senha.")
        String password,
        @NotNull(message = "Confirme a senha.")
        String confirmPassword,
        @Schema(description = "Precisa ser true")
        Boolean termsAccepted,
        @Schema(description = "Versão dos termos exibida na consulta do convite")
        @NotBlank(message = "Informe a versão dos termos.")
        @Size(max = 50, message = "A versão dos termos deve ter até 50 caracteres.")
        String termsVersion) {

    /** Token e senhas são segredos: ficam fora de qualquer toString acidental. */
    @Override
    public String toString() {
        return "InvitationNewUserAcceptRequest[token=***, password=***, confirmPassword=***, termsAccepted="
                + termsAccepted + "]";
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Campo não permitido: " + field);
    }
}
