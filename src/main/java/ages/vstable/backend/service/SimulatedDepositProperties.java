package ages.vstable.backend.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Crédito simulado de saldo (cicd#24): não há dinheiro real por trás do crédito, então ele só existe nos
 * ambientes que o habilitam explicitamente. Desligado por padrão; o perfil {@code dev} liga.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "balance.simulated-deposits")
public class SimulatedDepositProperties {

    private boolean enabled = false;
}
