package ages.vstable.backend.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/** Copies context at submission time and restores the worker's previous context. */
public final class LogContext implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restore(submitted);
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static void restore(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
