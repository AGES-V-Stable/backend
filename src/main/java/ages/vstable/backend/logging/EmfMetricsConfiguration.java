package ages.vstable.backend.logging;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Liga o emissor EMF (estático, como {@link OperationalLog}) conforme a configuração do ambiente. */
@Configuration
class EmfMetricsConfiguration {

    @Value("${observability.emf.enabled:false}")
    private boolean enabled;

    @Value("${observability.emf.namespace:VStable}")
    private String namespace;

    @PostConstruct
    void configure() {
        EmfMetrics.configure(enabled, namespace);
    }
}
