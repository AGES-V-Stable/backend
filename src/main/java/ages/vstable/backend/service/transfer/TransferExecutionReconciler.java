package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.repository.TransferExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Retoma execuções BlindPay paradas: webhook perdido, chamada sem resposta ou queda do
 * processo no meio de uma etapa. Cada etapa é repetida com a mesma chave de
 * idempotência, então a retomada nunca duplica payin nem payout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transfers.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransferExecutionReconciler {

    static final Set<TransferExecutionStatus> RESUMABLE = EnumSet.of(
            TransferExecutionStatus.PENDING_SUBMISSION,
            TransferExecutionStatus.AWAITING_FUNDS,
            TransferExecutionStatus.FUNDS_RECEIVED,
            TransferExecutionStatus.PAYOUT_SUBMITTING,
            TransferExecutionStatus.PAYOUT_PROCESSING);

    private final TransferExecutionRepository executionRepository;
    private final BlindPayExecutionProcessor processor;

    @Value("${transfers.reconciliation.stale-after:30s}")
    private Duration staleAfter;

    @Value("${transfers.reconciliation.batch-size:50}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${transfers.reconciliation.interval:30s}",
            fixedDelayString = "${transfers.reconciliation.interval:30s}")
    public void reconcile() {
        OffsetDateTime updatedBefore = OffsetDateTime.now(ZoneOffset.UTC).minus(staleAfter);
        for (UUID executionId : executionRepository.findIdsToReconcile(
                IntegrationProvider.BLINDPAY, RESUMABLE, updatedBefore, PageRequest.of(0, batchSize))) {
            try {
                processor.reconcile(executionId);
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile transfer execution {}: {}", executionId, ex.getMessage());
            }
        }
    }
}
