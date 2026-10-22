package ages.vstable.backend.logging;

import ages.vstable.backend.configuration.QuoteConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class LogContextTest {
    @AfterEach
    void clearContext() { MDC.clear(); }

    @Test
    void decoratedTaskUsesSubmissionContextAndRestoresWorkerEvenOnFailure() {
        MDC.put("requestId", "submitted");
        Runnable task = new LogContext().decorate(() -> {
            assertThat(MDC.get("requestId")).isEqualTo("submitted");
            MDC.put("temporary", "value");
            throw new IllegalStateException("synthetic-error");
        });
        MDC.clear();
        MDC.put("requestId", "worker");
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("requestId").containsEntry("requestId", "worker");
    }

    @Test
    void realQuoteExecutorPropagatesIdsAndDoesNotLeakAcrossReusedWorkers() throws Exception {
        var executor = (ThreadPoolTaskExecutor) new QuoteConfiguration().quoteProviderExecutor();
        executor.setCorePoolSize(1);
        try {
            MDC.put("requestId", "first");
            assertThat(executor.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS)).isEqualTo("first");
            MDC.put("requestId", "second");
            assertThatThrownBy(() -> executor.submit(() -> {
                assertThat(MDC.get("requestId")).isEqualTo("second");
                throw new IllegalStateException("synthetic-failure");
            }).get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            MDC.clear();
            assertThat(executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)).isNullOrEmpty();
        } finally {
            executor.shutdown();
        }
    }
}
