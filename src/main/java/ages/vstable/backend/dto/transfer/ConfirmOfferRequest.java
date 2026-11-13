package ages.vstable.backend.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Confirmação explícita do usuário: os valores exibidos para ele. A execução só segue
 * se forem exatamente os da oferta (ou da nova cotação, na reconfirmação), o que impede
 * executar com um preço diferente do que foi visto.
 *
 * <p>{@code totpCode} é o código de 6 dígitos do aplicativo autenticador. Ele autoriza a
 * operação financeira e é consumido somente quando a confirmação muda o estado da execução.
 */
public record ConfirmOfferRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal sourceAmount,
        @NotNull @DecimalMin(value = "0.01") BigDecimal targetAmount,
        @NotBlank(message = "TOTP code is required")
        @Pattern(regexp = "^\\d{6}$", message = "TOTP code must be 6 digits")
        String totpCode
) {

    /** Não expõe o código TOTP caso a requisição seja registrada em log. */
    @Override
    public String toString() {
        return "ConfirmOfferRequest[sourceAmount=" + sourceAmount + ", targetAmount=" + targetAmount
                + ", totpCode=***]";
    }
}
