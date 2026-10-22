package ages.vstable.backend.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.*;

class OperationalLogTest {
    // Exercise Spring's actual synchronization lifecycle without a database or external calls.
    private final AbstractPlatformTransactionManager transactions = new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    };

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void milestoneIsDeferredUntilCommitAndRetainsRequestContext() {
        try (var capture = new LogCapture(OperationalLog.class)) {
            var transaction = transactions.getTransaction(new DefaultTransactionDefinition());
            MDC.put("requestId", "original-request");
            OperationalLog.milestone("onboarding.completed", "success");
            assertThat(capture.events()).isEmpty();
            MDC.put("requestId", "commit-context");
            transactions.commit(transaction);
            assertThat(capture.events()).hasSize(1);
            assertThat(capture.events().getFirst().getMDCPropertyMap()).containsEntry("requestId", "original-request");
            assertThat(MDC.get("requestId")).isEqualTo("commit-context");
        }
    }

    @Test
    void rollbackSuppressesSuccessfulMilestones() {
        try (var capture = new LogCapture(OperationalLog.class)) {
            var transaction = transactions.getTransaction(new DefaultTransactionDefinition());
            OperationalLog.milestone("transfer.creation.completed", "success");
            transactions.rollback(transaction);
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void unexpectedDiagnosticsExcludeMessagesCausesAndSuppressedPayloads() {
        var cause = new IllegalArgumentException("private-financial-data");
        var error = new IllegalStateException("synthetic-password", cause);
        error.addSuppressed(new RuntimeException("synthetic-token"));
        try (var capture = new LogCapture(OperationalLog.class)) {
            OperationalLog.unexpected(error);
            var event = capture.events().getFirst();
            assertThat(LogCapture.fields(event).toString()).contains("IllegalStateException", "IllegalArgumentException", "OperationalLogTest")
                    .doesNotContain("private-financial-data", "synthetic-password", "synthetic-token");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }
}
