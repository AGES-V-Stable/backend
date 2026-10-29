package ages.vstable.backend.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Only fixed event names/classifications and technical metadata belong in these events. */
public final class OperationalLog {
    private static final Logger LOG = LoggerFactory.getLogger(OperationalLog.class);

    private OperationalLog() { }

    public static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public static void milestone(String event, String outcome) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable emit = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                LogContext.restore(context);
                LOG.atInfo().addKeyValue("event", event).addKeyValue("operation", event)
                        .addKeyValue("outcome", outcome).log("Business operation completed");
            } finally {
                LogContext.restore(previous);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit.run();
                }
            });
        } else {
            emit.run();
        }
    }

    public static String communicationOutcome(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return "timeout";
            }
        }
        return "communication_failure";
    }

    public static void provider(String provider, String operation, String outcome, Integer status, long startedAt) {
        var event = LOG.atLevel("success".equals(outcome) ? Level.INFO : Level.WARN)
                .addKeyValue("event", "provider.call.completed")
                .addKeyValue("provider", provider).addKeyValue("operation", operation)
                .addKeyValue("outcome", outcome).addKeyValue("durationMs", elapsedMillis(startedAt));
        if (status != null) {
            event.addKeyValue("status", status);
        }
        event.log("Provider call completed");
    }

    public static void quoteAggregation(String outcome, long startedAt, long timeouts) {
        LOG.atLevel("success".equals(outcome) ? Level.INFO : Level.WARN)
                .addKeyValue("event", "quote.aggregation.completed")
                .addKeyValue("operation", "quote.aggregate").addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", elapsedMillis(startedAt)).addKeyValue("timeoutCount", timeouts)
                .log("Quote aggregation completed");
    }

    public static void authentication(String outcome) {
        LOG.atWarn().addKeyValue("event", "authentication.rejected")
                .addKeyValue("operation", "authenticate").addKeyValue("outcome", outcome)
                .log("Authentication rejected");
    }

    public static void unexpected(Throwable failure) {
        // Never pass the original Throwable to a logger: its messages, causes and suppressed
        // exceptions may contain credentials or provider payloads. Keep only code locations.
        StringBuilder frames = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int cause = 0; current != null && cause < 8 && seen.add(current); cause++) {
            frames.append(current.getClass().getName()).append('\n');
            StackTraceElement[] trace = current.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 40); i++) {
                frames.append("\tat ").append(trace[i]).append('\n');
            }
            current = current.getCause();
        }
        LOG.atError().addKeyValue("event", "application.error")
                .addKeyValue("outcome", "unexpected_failure")
                .addKeyValue("exceptionType", failure.getClass().getName())
                .addKeyValue("stackFrames", frames.toString()).log("Unexpected application failure");
    }
}
