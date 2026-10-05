package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Detalhe de uma transação de pagamento (importação) para um beneficiário. */
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "import_transactions")
public class ImportTransactionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "transfer_method", columnDefinition = "transfer_method_enum", nullable = false)
    private TransferMethod transferMethod;
}
