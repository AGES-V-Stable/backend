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
        milestone(event, outcome, null, null);
    }

    public static void milestone(String event, String outcome, String entityType, java.util.UUID entityId) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable emit = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                LogContext.restore(context);
                var builder = LOG.atInfo().addKeyValue("event", event).addKeyValue("operation", event)
                        .addKeyValue("outcome", outcome);
                if (entityType != null && entityId != null)
                    builder.addKeyValue("entityType", entityType).addKeyValue("entityId", entityId);
                builder.log("Business operation completed");
                EmfMetrics.emit(EmfMetrics.dimensions("Operation", event, "Outcome", outcome),
                        EmfMetrics.Metric.count("BusinessOperation", 1));
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
        long durationMs = elapsedMillis(startedAt);
        var event = LOG.atLevel("success".equals(outcome) ? Level.INFO : Level.WARN)
                .addKeyValue("event", "provider.call.completed")
                .addKeyValue("provider", provider).addKeyValue("operation", operation)
                .addKeyValue("outcome", outcome).addKeyValue("durationMs", durationMs);
        if (status != null) {
            event.addKeyValue("status", status);
        }
        event.log("Provider call completed");
        // Sem "operation" na dimensão: cada combinação é uma métrica cobrada; o detalhe fica nos logs.
        EmfMetrics.emit(EmfMetrics.dimensions("Provider", provider, "Outcome", outcome),
                EmfMetrics.Metric.count("ProviderCall", 1), EmfMetrics.Metric.millis("ProviderCallDuration", durationMs));
    }

    /** Requisição HTTP concluída; route é o padrão da rota (nunca o caminho real) e statusClass é 2xx/4xx/5xx. */
    public static void httpRequest(String route, String statusClass, long durationMs) {
        EmfMetrics.emit(EmfMetrics.dimensions("Route", route, "StatusClass", statusClass),
                EmfMetrics.Metric.count("HttpRequest", 1), EmfMetrics.Metric.millis("HttpRequestDuration", durationMs));
    }

    /**
     * Uma execução do job de conciliação de transferências. Serve de heartbeat: a ausência desta métrica
     * indica que o job parou, o que não aparece em nenhum outro lugar (nada falha, nada é registrado).
     */
    public static void reconciliationRun(int found, int failed, long startedAt) {
        long durationMs = elapsedMillis(startedAt);
        LOG.atLevel(failed > 0 ? Level.WARN : Level.INFO)
                .addKeyValue("event", "transfer.reconciliation.completed")
                .addKeyValue("operation", "transfer.reconciliation")
                .addKeyValue("outcome", failed > 0 ? "partial_failure" : "success")
                .addKeyValue("found", found).addKeyValue("failed", failed).addKeyValue("durationMs", durationMs)
                .log("Transfer reconciliation run completed");
        EmfMetrics.emit(EmfMetrics.dimensions(),
                EmfMetrics.Metric.count("ReconciliationRun", 1),
                EmfMetrics.Metric.count("ReconciliationExecutionsFound", found),
                EmfMetrics.Metric.count("ReconciliationFailures", failed),
                EmfMetrics.Metric.millis("ReconciliationDuration", durationMs));
    }

    /** Atualização de uma cotação de referência; pair é o par configurado (ex.: BRL/USDC). */
    public static void exchangeRateRefresh(String pair, String outcome) {
        LOG.atLevel("success".equals(outcome) ? Level.INFO : Level.WARN)
                .addKeyValue("event", "exchange_rate.refresh.completed")
                .addKeyValue("operation", "exchange_rate.refresh").addKeyValue("pair", pair)
                .addKeyValue("outcome", outcome)
                .log("Exchange rate refresh completed");
        EmfMetrics.emit(EmfMetrics.dimensions("Pair", pair, "Outcome", outcome),
                EmfMetrics.Metric.count("ExchangeRateRefresh", 1));
    }

    public static void quoteAggregation(String outcome, long startedAt, long timeouts) {
        long durationMs = elapsedMillis(startedAt);
        LOG.atLevel("success".equals(outcome) ? Level.INFO : Level.WARN)
                .addKeyValue("event", "quote.aggregation.completed")
                .addKeyValue("operation", "quote.aggregate").addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", durationMs).addKeyValue("timeoutCount", timeouts)
                .log("Quote aggregation completed");
        EmfMetrics.emit(EmfMetrics.dimensions("Outcome", outcome),
                EmfMetrics.Metric.count("QuoteAggregation", 1),
                EmfMetrics.Metric.millis("QuoteAggregationDuration", durationMs),
                EmfMetrics.Metric.count("QuoteProviderTimeouts", timeouts));
    }

    public static void authentication(String outcome) {
        LOG.atWarn().addKeyValue("event", "authentication.rejected")
                .addKeyValue("operation", "authenticate").addKeyValue("outcome", outcome)
                .log("Authentication rejected");
        EmfMetrics.emit(EmfMetrics.dimensions("Outcome", outcome), EmfMetrics.Metric.count("AuthenticationRejected", 1));
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
        EmfMetrics.emit(EmfMetrics.dimensions(), EmfMetrics.Metric.count("UnexpectedError", 1));
    }
}
