package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;


/** Detalhe de uma transação de recebimento (exportação) de um pagador externo. */
@Entity
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "export_transactions")
@PrimaryKeyJoinColumn(name = "transaction_id")
public class ExportTransactionEntity extends BaseTransactionEntity {

    @Column(name = "external_billing_code", nullable = false, length = 255)
    private String externalBillingCode;

    @Column(name = "external_payer_name", nullable = false, length = 255)
    private String externalPayerName;

    @Column(name = "external_payer_email", length = 255)
    private String externalPayerEmail;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "reason_description", nullable = false, columnDefinition = "text")
    private String reasonDescription;
}
