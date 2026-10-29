package ages.vstable.backend.external.blindpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** URL da página hospedada em que o cliente aceita os termos da BlindPay. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlindPayInitiateTosResponse(String url) {
}
