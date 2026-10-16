package ages.vstable.backend.external.blindpay;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "blindpay")
public class BlindPayProperties {

    private String baseUrl;
    private String apiKey;
    private String instanceId;
    private String webhookSecret;
    /** Para onde a página hospedada de aceite dos termos redireciona; opcional. */
    private String tosRedirectUrl;
}
