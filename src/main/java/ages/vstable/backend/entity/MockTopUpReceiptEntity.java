package ages.vstable.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Resultado original de um crédito simulado, persistido atomicamente com o saldo. */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "mock_topup_receipts", uniqueConstraints = @UniqueConstraint(
        name = "mock_topup_receipts_unique_company_request",
        columnNames = {"company_id", "request_key"}))
public class MockTopUpReceiptEntity {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "request_key", nullable = false, length = 128, updatable = false)
    private String requestKey;

    @Column(name = "amount_brl", precision = 15, scale = 2, nullable = false, updatable = false)
    private BigDecimal amountBrl;

    @Column(name = "resulting_balance_brl", precision = 15, scale = 2, nullable = false, updatable = false)
    private BigDecimal resultingBalanceBrl;
}
