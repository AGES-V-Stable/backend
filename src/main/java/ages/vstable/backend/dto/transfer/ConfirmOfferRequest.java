package ages.vstable.backend.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Confirmação explícita do usuário: os valores exibidos para ele. A execução só segue
 * se forem exatamente os da oferta (ou da nova cotação, na reconfirmação), o que impede
 * executar com um preço diferente do que foi visto.
 */
public record ConfirmOfferRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal sourceAmount,
        @NotNull @DecimalMin(value = "0.01") BigDecimal targetAmount
) {
}
