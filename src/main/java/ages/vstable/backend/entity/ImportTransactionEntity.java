package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Detalhe de uma transação de pagamento (importação) para um beneficiário. */
@Entity
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "import_transactions")
@PrimaryKeyJoinColumn(name = "transaction_id")
public class ImportTransactionEntity extends BaseTransactionEntity {

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", insertable = false, updatable = false)
    private BeneficiaryEntity beneficiary;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "transfer_method", columnDefinition = "transfer_method_enum", nullable = false)
    private TransferMethod transferMethod;
}
