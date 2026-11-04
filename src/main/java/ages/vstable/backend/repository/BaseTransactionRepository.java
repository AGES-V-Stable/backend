package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BaseTransactionRepository
        extends JpaRepository<BaseTransactionEntity, UUID>,
        JpaSpecificationExecutor<BaseTransactionEntity> {

    /**
     * Soma em BRL das transações com o status dado, criadas em [{@code from}, {@code to}); sem
     * {@code companyId}, soma todas as empresas. {@code null} quando não há nenhuma.
     */
    @Query("""
            select sum(t.settlementAmountBrl)
            from BaseTransactionEntity t
            where (:companyId is null or t.companyId = :companyId)
              and t.status = :status
              and t.createdAt >= :from
              and t.createdAt < :to
            """)
    BigDecimal sumSettlementAmountBrlCreatedBetween(@Param("companyId") UUID companyId,
                                                    @Param("status") TransactionStatus status,
                                                    @Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to);

    /**
     * Totais mensais das transações liquidadas em [{@code from}, {@code to}), datadas pela liquidação
     * ou, sem ela, pela criação. A economia segue {@code SavingsEstimator}, arredondada por operação
     * (o ROUND do PostgreSQL afasta do zero, como HALF_UP). Sem {@code companyId},
     * consolida todas as empresas.
     */
    @Query(value = """
            SELECT to_char(COALESCE(t.settled_at, t.created_at) AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') AS "month",
                   SUM(t.settlement_amount_brl) AS "volumeBrl",
                   COUNT(*) FILTER (WHERE t.settlement_amount_brl IS NULL) AS "volumeMissing",
                   SUM(ROUND((t.settlement_amount_brl - t.service_fee_brl) * :referenceSpreadPercentage / 100 - t.service_fee_brl, 2))
                       FILTER (WHERE t.settlement_amount_brl IS NOT NULL AND t.service_fee_brl IS NOT NULL) AS "savingsBrl",
                   COUNT(*) FILTER (WHERE t.settlement_amount_brl IS NULL OR t.service_fee_brl IS NULL) AS "savingsMissing",
                   SUM(t.effective_spread_percentage) AS "spreadSum",
                   COUNT(t.effective_spread_percentage) AS "spreadCount"
            FROM base_transactions t
            WHERE t.status = 'SETTLED'
              AND (CAST(:companyId AS uuid) IS NULL OR t.company_id = CAST(:companyId AS uuid))
              AND COALESCE(t.settled_at, t.created_at) >= :from
              AND COALESCE(t.settled_at, t.created_at) < :to
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<DashboardMonthlyTotals> findMonthlyDashboardTotals(@Param("companyId") UUID companyId,
                                                            @Param("from") OffsetDateTime from,
                                                            @Param("to") OffsetDateTime to,
                                                            @Param("referenceSpreadPercentage") BigDecimal referenceSpreadPercentage);

    /** Transações de qualquer status em [{@code from}, {@code to}), da mais recente para a mais antiga. */
    @Query(value = """
            select t from BaseTransactionEntity t
            where (:companyId is null or t.companyId = :companyId)
              and coalesce(t.settledAt, t.createdAt) >= :from
              and coalesce(t.settledAt, t.createdAt) < :to
            order by coalesce(t.settledAt, t.createdAt) desc, t.id desc
            """,
            countQuery = """
            select count(t) from BaseTransactionEntity t
            where (:companyId is null or t.companyId = :companyId)
              and coalesce(t.settledAt, t.createdAt) >= :from
              and coalesce(t.settledAt, t.createdAt) < :to
            """)
    Page<BaseTransactionEntity> findLatestForDashboard(@Param("companyId") UUID companyId,
                                                       @Param("from") OffsetDateTime from,
                                                       @Param("to") OffsetDateTime to,
                                                       Pageable pageable);
}
