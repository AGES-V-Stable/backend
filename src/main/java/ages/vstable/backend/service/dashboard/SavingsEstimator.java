package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Economia estimada de uma operação frente ao câmbio bancário de referência:
 * {@code (settlementAmountBrl − serviceFeeBrl) × referência% − serviceFeeBrl}. O valor convertido
 * desconta a tarifa porque a Avenia inclui a tarifa no valor de entrada. Pode ser negativa.
 * As agregações de {@code BaseTransactionRepository#findMonthlyDashboardTotals} usam a mesma fórmula.
 */
@Component
public class SavingsEstimator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal referenceSpreadPercentage;

    public SavingsEstimator(DashboardProperties properties) {
        BigDecimal reference = properties.getSavings().getReferenceSpreadPercentage();
        if (reference == null || reference.signum() < 0) {
            throw new IllegalStateException("dashboard.savings.reference-spread-percentage must be zero or positive");
        }
        this.referenceSpreadPercentage = reference;
    }

    public BigDecimal referenceSpreadPercentage() {
        return referenceSpreadPercentage;
    }

    /** Economia de uma transação liquidada; {@code null} para outros status ou sem valor e tarifa. */
    public BigDecimal estimate(BaseTransactionEntity transaction) {
        if (transaction.getStatus() != TransactionStatus.SETTLED) {
            return null;
        }
        return estimate(transaction.getSettlementAmountBrl(), transaction.getServiceFeeBrl());
    }

    /** {@code null} quando a operação não registrou o valor em BRL ou a tarifa. */
    public BigDecimal estimate(BigDecimal settlementAmountBrl, BigDecimal serviceFeeBrl) {
        if (settlementAmountBrl == null || serviceFeeBrl == null) {
            return null;
        }
        return settlementAmountBrl.subtract(serviceFeeBrl)
                .multiply(referenceSpreadPercentage)
                .divide(ONE_HUNDRED)
                .subtract(serviceFeeBrl)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
