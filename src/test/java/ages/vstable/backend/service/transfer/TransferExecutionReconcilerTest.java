package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.repository.TransferExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferExecutionReconcilerTest {

    @Mock private TransferExecutionRepository executionRepository;
    @Mock private BlindPayExecutionProcessor processor;

    private TransferExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new TransferExecutionReconciler(executionRepository, processor);
        ReflectionTestUtils.setField(reconciler, "staleAfter", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(reconciler, "batchSize", 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcile_resumesOnlyNonTerminalBlindPayExecutions() {
        when(executionRepository.findIdsToReconcile(eq(IntegrationProvider.BLINDPAY), any(), any(), any()))
                .thenReturn(List.of());

        reconciler.reconcile();

        ArgumentCaptor<Collection<TransferExecutionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(executionRepository).findIdsToReconcile(eq(IntegrationProvider.BLINDPAY), statuses.capture(), any(), any());
        assertThat(statuses.getValue())
                .doesNotContain(TransferExecutionStatus.COMPLETED, TransferExecutionStatus.FAILED,
                        TransferExecutionStatus.REFUNDED, TransferExecutionStatus.REQUOTE_REQUIRED,
                        TransferExecutionStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void reconcile_failureInOneExecution_doesNotStopTheOthers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(executionRepository.findIdsToReconcile(any(), any(), any(), any())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("boom")).when(processor).reconcile(first);

        reconciler.reconcile();

        verify(processor).reconcile(second);
    }
}
