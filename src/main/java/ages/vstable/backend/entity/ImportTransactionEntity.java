package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.TransferMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Linha "filha" de {@link BaseTransactionEntity} para transferências que pagam um
 * beneficiário (o caso de POST /v1/transfers). {@code transactionId} é ao mesmo tempo
 * chave primária e FK para base_transactions.id — não gerado, copiado do id da base
 * já salva.
 */
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "import_transactions")
public class ImportTransactionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private BeneficiaryEntity beneficiary;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "transfer_method", columnDefinition = "transfer_method_enum", nullable = false)
    private TransferMethod transferMethod;
}
