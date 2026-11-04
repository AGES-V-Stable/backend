package ages.vstable.backend.service.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsEstimatorTest {

    private static SavingsEstimator estimator(String referencePercentage) {
        DashboardProperties properties = new DashboardProperties();
        properties.getSavings().setReferenceSpreadPercentage(
                referencePercentage == null ? null : new BigDecimal(referencePercentage));
        return new SavingsEstimator(properties);
    }

    @ParameterizedTest
    @CsvSource({
            // referência, valor em BRL, tarifa, economia
            "4.00, 1000.00, 10.00, 29.60",
            "4.00, 333.33, 0.00, 13.33",
            "4.00, 100.00, 10.00, -6.40",
            "2.50, 1000.00, 0.00, 25.00",
            "0, 1000.00, 10.00, -10.00",
    })
    void estimate_discountsTheFeeFromTheConvertedAmountAndFromTheReferenceCost(
            String reference, String amountBrl, String feeBrl, String expected) {
        BigDecimal savings = estimator(reference).estimate(new BigDecimal(amountBrl), new BigDecimal(feeBrl));

        assertThat(savings).isEqualByComparingTo(expected);
        assertThat(savings.scale()).isEqualTo(2);
    }

    @Test
    void estimate_returnsNullWithoutAmountOrFee() {
        SavingsEstimator estimator = estimator("4.00");

        assertThat(estimator.estimate(null, BigDecimal.ONE)).isNull();
        assertThat(estimator.estimate(BigDecimal.TEN, null)).isNull();
    }

    @Test
    void constructor_rejectsMissingOrNegativeReference() {
        assertThatThrownBy(() -> estimator(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> estimator("-1")).isInstanceOf(IllegalStateException.class);
    }
}
