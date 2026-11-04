package ages.vstable.backend.service.dashboard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {

    private Savings savings = new Savings();

    @Getter
    @Setter
    public static class Savings {
        /** Custo de câmbio bancário usado como referência da economia, em pontos percentuais (4.00 = 4%). */
        private BigDecimal referenceSpreadPercentage;
    }
}
