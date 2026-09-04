package ages.vstable.backend.dto.representante;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RepresentanteCreateRequest {

    @NotBlank
    private String nomeCompleto;

    @NotBlank
    @Pattern(regexp = "\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}", message = "CPF deve ter 11 dígitos")
    private String cpf;

    @NotNull
    private LocalDate dataNascimento;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\(?\\d{2}\\)?\\s?\\d{4,5}-?\\d{4}", message = "Telefone em formato inválido")
    private String telefone;

    @NotBlank
    private String cargo;

    @NotBlank
    private String paisResidencia;
}
