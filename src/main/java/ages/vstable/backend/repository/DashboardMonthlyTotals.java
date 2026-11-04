package ages.vstable.backend.repository;

import java.math.BigDecimal;

/** Totais mensais das transações liquidadas, calculados no banco para o dashboard. */
public interface DashboardMonthlyTotals {

    /** Mês civil em America/Sao_Paulo, no formato AAAA-MM. */
    String getMonth();

    /** Soma em BRL; {@code null} quando nenhuma transação do mês registrou o valor. */
    BigDecimal getVolumeBrl();

    long getVolumeMissing();

    /** Soma da economia estimada; {@code null} quando nenhuma transação do mês tem valor e tarifa. */
    BigDecimal getSavingsBrl();

    long getSavingsMissing();

    BigDecimal getSpreadSum();

    long getSpreadCount();
}
