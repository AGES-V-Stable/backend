package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Execução de uma oferta ({@link ProviderQuoteEntity}) confirmada pelo usuário. Os
 * valores gravados são os confirmados; os IDs externos permitem conciliar com o
 * provedor e reenviar chamadas com a mesma chave de idempotência.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transfer_executions")
public class TransferExecutionEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "provider_quote_id", nullable = false, unique = true, updatable = false)
    private UUID providerQuoteId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "beneficiary_id", nullable = false, updatable = false)
    private UUID beneficiaryId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50, updatable = false)
    private IntegrationProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TransferExecutionStatus status;

    @Column(name = "source_currency", nullable = false, length = 10)
    private String sourceCurrency;

    @Column(name = "target_currency", nullable = false, length = 10)
    private String targetCurrency;

    @Column(name = "source_amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal sourceAmount;

    @Column(name = "target_amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal targetAmount;

    @Column(name = "total_fee", precision = 20, scale = 8)
    private BigDecimal totalFee;

    /** Stablecoins efetivamente creditadas na carteira gerenciada pelo payin. */
    @Column(name = "funded_amount", precision = 20, scale = 8)
    private BigDecimal fundedAmount;

    @Column(name = "external_payin_quote_id")
    private String externalPayinQuoteId;

    @Column(name = "external_payin_id")
    private String externalPayinId;

    /** Código PIX (ou instrução equivalente) devolvido pelo payin para o usuário pagar. */
    @Column(name = "funding_instructions", columnDefinition = "text")
    private String fundingInstructions;

    @Column(name = "external_payout_quote_id")
    private String externalPayoutQuoteId;

    @Column(name = "payout_quote_sender_amount", precision = 20, scale = 8)
    private BigDecimal payoutQuoteSenderAmount;

    @Column(name = "payout_quote_receiver_amount", precision = 20, scale = 8)
    private BigDecimal payoutQuoteReceiverAmount;

    @Column(name = "payout_quote_expires_at")
    private OffsetDateTime payoutQuoteExpiresAt;

    /** Quantas recotações de payout já foram pedidas; compõe a chave de idempotência. */
    @Column(name = "payout_quote_attempts", nullable = false)
    private int payoutQuoteAttempts;

    @Column(name = "external_payout_id")
    private String externalPayoutId;

    @Column(name = "external_ticket_id")
    private String externalTicketId;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
