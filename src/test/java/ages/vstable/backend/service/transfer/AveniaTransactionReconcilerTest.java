package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.external.avenia.AveniaGateway;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.CompanyFinancialAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AveniaTransactionReconcilerTest {
    @Mock private BaseTransactionRepository transactionRepository;
    @Mock private TransferExecutionRepository executionRepository;
    @Mock private AveniaGateway gateway;
    @Mock private CompanyFinancialAccountService financialAccounts;
    @Mock private PlatformTransactionManager transactionManager;

    private AveniaTransactionReconciler reconciler;
    private BaseTransactionEntity transaction;

    @BeforeEach
    void setUp() {
        reconciler = new AveniaTransactionReconciler(transactionRepository, executionRepository,
                gateway, financialAccounts, transactionManager);
        ReflectionTestUtils.setField(reconciler, "staleAfter", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(reconciler, "batchSize", 5);
        transaction = BaseTransactionEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID())
                .aveniaTicketId(UUID.randomUUID()).status(TransactionStatus.PROCESSING).build();
    }

    private void ticket(String status) {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(financialAccounts.requireAveniaSubAccountId(transaction.getCompanyId())).thenReturn("company-subaccount");
        when(gateway.getTicket(transaction.getAveniaTicketId(), "company-subaccount"))
                .thenReturn(new AveniaTicketResponse(transaction.getAveniaTicketId(), null, null, status, null, null));
    }

    @Test
    void paidTicketSettlesTransactionAndCompletesExecution() {
        ticket("PAID");
        when(transactionRepository.findForUpdate(transaction.getId())).thenReturn(Optional.of(transaction));
        TransferExecutionEntity execution = TransferExecutionEntity.builder().id(UUID.randomUUID())
                .status(TransferExecutionStatus.SUBMITTED).build();
        when(executionRepository.findByTransactionIdAndProvider(transaction.getId(), IntegrationProvider.AVENIA))
                .thenReturn(Optional.of(execution));
        when(executionRepository.findForUpdate(execution.getId())).thenReturn(Optional.of(execution));

        reconciler.reconcile(transaction.getId());

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(transaction.getSettledAt()).isNull();
        assertThat(execution.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(transactionManager).commit(any());
        verify(gateway, never()).createTicket(any());
    }

    @ParameterizedTest
    @CsvSource({"UNPAID,AWAITING_PAYMENT", "PROCESSING,PROCESSING", "ON-HOLD,HELD",
            "FAILED,FAILED", "PARTIAL-FAILED,PARTIAL_FAILURE", "CANCELED,CANCELED", "PAID,SETTLED"})
    void reconcilesLegacyTransactionsWithoutAnExecution(String providerStatus, TransactionStatus expected) {
        ticket(providerStatus);
        when(transactionRepository.findForUpdate(transaction.getId())).thenReturn(Optional.of(transaction));

        reconciler.reconcile(transaction.getId());

        assertThat(transaction.getStatus()).isEqualTo(expected);
        assertThat(transaction.getUpdatedAt()).isNotNull();
    }

    @Test
    void unknownStatusLeavesTransactionUnchanged() {
        ticket("NEW_STATUS");

        assertThatThrownBy(() -> reconciler.reconcile(transaction.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        verify(transactionRepository, never()).findForUpdate(any());
    }

    @Test
    void mismatchedTicketIsNotApplied() {
        ticket("PAID");
        when(gateway.getTicket(transaction.getAveniaTicketId(), "company-subaccount"))
                .thenReturn(new AveniaTicketResponse(UUID.randomUUID(), null, null, "PAID", null, null));

        assertThatThrownBy(() -> reconciler.reconcile(transaction.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void terminalTransactionIsNotPolled() {
        transaction.setStatus(TransactionStatus.SETTLED);
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        reconciler.reconcile(transaction.getId());

        verifyNoInteractions(gateway, financialAccounts);
    }

    @Test
    void concurrentTerminalUpdateIsNotOverwritten() {
        ticket("PROCESSING");
        BaseTransactionEntity settled = BaseTransactionEntity.builder().status(TransactionStatus.SETTLED).build();
        when(transactionRepository.findForUpdate(transaction.getId())).thenReturn(Optional.of(settled));

        reconciler.reconcile(transaction.getId());

        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        verifyNoInteractions(executionRepository);
    }

    @Test
    void providerFailureDoesNotStopOtherTicketsAndRotatesFailedTicket() {
        UUID failed = UUID.randomUUID();
        when(transactionRepository.findAveniaIdsToReconcile(eq(AveniaTransactionReconciler.PENDING), any(), any()))
                .thenReturn(List.of(failed, transaction.getId()));
        when(transactionRepository.findById(failed)).thenThrow(new IllegalStateException("unavailable"));
        BaseTransactionEntity failedTransaction = BaseTransactionEntity.builder().status(TransactionStatus.PROCESSING).build();
        when(transactionRepository.findForUpdate(failed)).thenReturn(Optional.of(failedTransaction));
        ticket("PAID");
        when(transactionRepository.findForUpdate(transaction.getId())).thenReturn(Optional.of(transaction));

        reconciler.reconcile();

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(failedTransaction.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(failedTransaction.getUpdatedAt()).isNotNull();
    }
}
