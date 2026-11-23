package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.external.avenia.AveniaGateway;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.CompanyFinancialAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Consulta tickets existentes, sem reenviar pagamentos nem criar novos tickets. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "transfers.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AveniaTransactionReconciler {

    static final Set<TransactionStatus> PENDING = EnumSet.of(
            TransactionStatus.AWAITING_PAYMENT, TransactionStatus.PROCESSING, TransactionStatus.HELD);

    private final BaseTransactionRepository transactionRepository;
    private final TransferExecutionRepository executionRepository;
    private final AveniaGateway gateway;
    private final CompanyFinancialAccountService financialAccounts;
    private final TransactionTemplate transactions;

    @Value("${transfers.reconciliation.stale-after:30s}")
    private Duration staleAfter;

    @Value("${transfers.reconciliation.batch-size:50}")
    private int batchSize;

    public AveniaTransactionReconciler(BaseTransactionRepository transactionRepository,
                                       TransferExecutionRepository executionRepository,
                                       AveniaGateway gateway,
                                       CompanyFinancialAccountService financialAccounts,
                                       PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.executionRepository = executionRepository;
        this.gateway = gateway;
        this.financialAccounts = financialAccounts;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelayString = "${transfers.reconciliation.interval:30s}",
            fixedDelayString = "${transfers.reconciliation.interval:30s}")
    public void reconcile() {
        for (UUID id : transactionRepository.findAveniaIdsToReconcile(PENDING,
                now().minus(staleAfter), PageRequest.of(0, batchSize))) {
            try {
                // Rotaciona o lote mesmo em falhas persistentes de um ticket/subconta.
                transactions.executeWithoutResult(status -> transactionRepository.findForUpdate(id)
                        .filter(t -> PENDING.contains(t.getStatus()))
                        .ifPresent(t -> t.setUpdatedAt(now())));
                reconcile(id);
            } catch (RuntimeException ex) {
                ages.vstable.backend.logging.OperationalLog.milestone("transfer.reconciliation.completed", "failure", "transfer", id);
                // Não registrar respostas externas: podem conter dados financeiros ou pessoais.
                log.warn("Could not reconcile Avenia transaction {} ({})", id, ex.getClass().getSimpleName());
            }
        }
    }

    void reconcile(UUID id) {
        BaseTransactionEntity snapshot = transactionRepository.findById(id).orElse(null);
        if (snapshot == null || snapshot.getAveniaTicketId() == null || !PENDING.contains(snapshot.getStatus())) {
            return;
        }
        String subAccountId = financialAccounts.requireAveniaSubAccountId(snapshot.getCompanyId());
        AveniaTicketResponse ticket = gateway.getTicket(snapshot.getAveniaTicketId(), subAccountId);
        if (ticket == null || !snapshot.getAveniaTicketId().equals(ticket.id())) {
            throw new IllegalStateException("Avenia ticket does not match the transaction");
        }
        TransactionStatus next = mapStatus(ticket.status());
        if (next == null) {
            throw new IllegalStateException("Unrecognized Avenia ticket status");
        }
        transactions.executeWithoutResult(status -> transactionRepository.findForUpdate(id)
                .filter(t -> PENDING.contains(t.getStatus()))
                .filter(t -> snapshot.getAveniaTicketId().equals(t.getAveniaTicketId()))
                .ifPresent(t -> {
                    OffsetDateTime observedAt = now();
                    if (t.getStatus() != next) {
                        ages.vstable.backend.logging.OperationalLog.milestone("transfer.status.changed",
                                next.name().toLowerCase(java.util.Locale.ROOT), "transfer", t.getId());
                    }
                    t.setStatus(next);
                    t.setUpdatedAt(observedAt);
                    // O DTO não fornece a data efetiva de liquidação; não inventar settledAt.
                    executionRepository.findByTransactionIdAndProvider(id, IntegrationProvider.AVENIA)
                            .flatMap(e -> executionRepository.findForUpdate(e.getId()))
                            .filter(e -> e.getStatus() == TransferExecutionStatus.SUBMITTED)
                            .ifPresent(e -> {
                                e.setStatus(switch (next) {
                                    case SETTLED -> TransferExecutionStatus.COMPLETED;
                                    case FAILED, PARTIAL_FAILURE, CANCELED -> TransferExecutionStatus.FAILED;
                                    default -> TransferExecutionStatus.SUBMITTED;
                                });
                                e.setUpdatedAt(observedAt);
                                if (e.getStatus() == TransferExecutionStatus.COMPLETED && e.getCompletedAt() == null) {
                                    e.setCompletedAt(observedAt);
                                }
                            });
                }));
    }

    private static TransactionStatus mapStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "UNPAID" -> TransactionStatus.AWAITING_PAYMENT;
            case "PROCESSING" -> TransactionStatus.PROCESSING;
            case "ON_HOLD", "ON-HOLD" -> TransactionStatus.HELD;
            case "PAID" -> TransactionStatus.SETTLED;
            case "FAILED" -> TransactionStatus.FAILED;
            case "PARTIAL_FAILED", "PARTIAL-FAILED" -> TransactionStatus.PARTIAL_FAILURE;
            case "CANCELED" -> TransactionStatus.CANCELED;
            default -> null;
        };
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
