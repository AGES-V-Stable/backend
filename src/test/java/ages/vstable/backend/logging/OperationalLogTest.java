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

    @Test
    void elapsedMillisComputesPositiveDurationFromNanoTimestamp() {
        long startedAt = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);

        long elapsed = OperationalLog.elapsedMillis(startedAt);

        assertThat(elapsed).isBetween(20L, 1000L);
    }

    @Test
    void authenticationEmitsWarningEvent() {
        try (var capture = new LogCapture(OperationalLog.class)) {
            OperationalLog.authentication("invalid_token");

            assertThat(capture.events()).hasSize(1);
            assertThat(LogCapture.fields(capture.events().getFirst()))
                    .containsEntry("event", "authentication.rejected")
                    .containsEntry("outcome", "invalid_token");
        }
    }

    @Test
    void unexpectedCapsCausesAtEightAndStackFramesAtForty() {
        Throwable current = new RuntimeException("cause-9");
        for (int i = 8; i >= 0; i--) {
            Throwable next = new RuntimeException("cause-" + i, current);
            StackTraceElement[] trace = new StackTraceElement[45];
            for (int f = 0; f < 45; f++) {
                trace[f] = new StackTraceElement("CauseClass" + i, "methodFrame" + f, "CauseClass" + i + ".java", f + 1);
            }
            next.setStackTrace(trace);
            current = next;
        }

        try (var capture = new LogCapture(OperationalLog.class)) {
            OperationalLog.unexpected(current);

            String stackFrames = String.valueOf(LogCapture.fields(capture.events().getFirst()).get("stackFrames"));
            assertThat(stackFrames)
                    .contains("CauseClass0", "CauseClass7", "methodFrame0", "methodFrame39")
                    .doesNotContain("CauseClass8", "CauseClass9", "methodFrame40");
        }
    }
}
