package ages.vstable.backend.external.avenia;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "avenia")
public class AveniaProperties {

    private String baseUrl;
    private String email;
    private String password;
    private String webhookUrl;
}
