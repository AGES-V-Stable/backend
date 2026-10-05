package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/** Detalhe de uma transação de recebimento (exportação) de um pagador externo. */
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "export_transactions")
public class ExportTransactionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "external_billing_code", nullable = false, unique = true, length = 255)
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
