package ages.vstable.backend.repository;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface BaseTransactionRepository
        extends JpaRepository<BaseTransactionEntity, UUID>,
        JpaSpecificationExecutor<BaseTransactionEntity> {

    /** Soma em BRL das transações da empresa com o status dado; {@code null} quando não há nenhuma. */
    @Query("""
            select sum(t.settlementAmountBrl)
            from BaseTransactionEntity t
            where t.companyId = :companyId
              and t.status = :status
            """)
    BigDecimal sumSettlementAmountBrl(@Param("companyId") UUID companyId,
                                      @Param("status") TransactionStatus status);

    /** Igual a {@link #sumSettlementAmountBrl}, considerando só transações criadas a partir de {@code startsAt}. */
    @Query("""
            select sum(t.settlementAmountBrl)
            from BaseTransactionEntity t
            where t.companyId = :companyId
              and t.status = :status
              and t.createdAt >= :startsAt
            """)
    BigDecimal sumSettlementAmountBrlSince(@Param("companyId") UUID companyId,
                                           @Param("status") TransactionStatus status,
                                           @Param("startsAt") OffsetDateTime startsAt);
}
