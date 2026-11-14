package ages.vstable.backend.service.exchangerate;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "exchange-rates")
public class ExchangeRateProperties {

    /** Idade máxima da cotação salva antes de a leitura buscar uma nova na Avenia. */
    private Duration ttl = Duration.ofHours(2);

    /** Espera máxima por par; abaixo do limite de 29 s do API Gateway mesmo com vários pares. */
    private Duration providerTimeout = Duration.ofSeconds(5);

    /** Valor de entrada da cotação: a Avenia exige inputAmount ou outputAmount. */
    private BigDecimal referenceAmount = new BigDecimal("1000");

    private List<Pair> pairs = new ArrayList<>();

    /** Rota cotada na Avenia; a taxa exibida é baseCurrency por 1 quoteCurrency. */
    @Getter
    @Setter
    public static class Pair {
        private String baseCurrency;
        private String quoteCurrency;
        private String inputPaymentMethod = "PIX";
        private String outputPaymentMethod;
    }
}
