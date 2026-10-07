package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "import_transactions")
@PrimaryKeyJoinColumn(name = "transaction_id")
public class ImportTransactionEntity extends BaseTransactionEntity {

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", insertable = false, updatable = false)
    private BeneficiaryEntity beneficiary;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_method", nullable = false)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private TransferMethod transferMethod;
}

