package ages.vstable.backend.dto.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Dados para convidar uma pessoa. Campos fora desta lista são recusados (400), para que ninguém envie
 * empresa, autor ou permissões por conta própria. Nome e e-mail chegam normalizados à validação.
 */
public record InvitationCreateRequest(
        @Schema(description = "Nome indicado pelo proprietário, de 2 a 255 caracteres", example = "Mariana Costa")
        @NotBlank(message = "Informe o nome do convidado.")
        @Size(min = 2, max = 255, message = "O nome deve ter de 2 a 255 caracteres.")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "O nome não pode ter caracteres de controle.")
        String name,
        @Schema(description = "E-mail do convidado, normalizado em minúsculas; identifica o login",
                example = "mariana@agrosul.com.br")
        @NotBlank(message = "Informe o e-mail do convidado.")
        @Email(message = "Informe um e-mail válido.")
        @Size(max = 255, message = "O e-mail deve ter até 255 caracteres.")
        String email,
        @Schema(description = "OPERATOR ou VIEWER")
        @NotNull(message = "Informe o perfil do convidado.")
        InviteRole role) {

    public InvitationCreateRequest {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Nome e e-mail são dados pessoais: ficam fora de qualquer toString acidental. */
    @Override
    public String toString() {
        return "InvitationCreateRequest[role=" + role + "]";
    }

    /** O Jackson do Spring ignora campos desconhecidos por padrão; aqui qualquer um deles falha a leitura. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Campo não permitido: " + field);
    }
}
