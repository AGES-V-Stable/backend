package ages.vstable.backend.configuration.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Janela móvel de tentativas por cliente: no máximo {@link #MAX_ATTEMPTS} em {@link #WINDOW}. Tentativas
 * recusadas não contam. O estado fica na memória da instância e acompanha no máximo
 * {@link #MAX_TRACKED_CLIENTS} clientes: ao atingir o teto, os sem tentativa recente são descartados e, se ainda
 * assim não houver espaço, clientes novos são recusados até a janela passar (falha fechada, memória limitada).
 */
final class InvitationAttemptLimiter {

    static final int MAX_ATTEMPTS = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    InvitationAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Registra a tentativa e devolve 0, ou devolve em quantos segundos (ao menos 1) o cliente pode tentar. */
    long tryAcquire(String client) {
        long now = clock.millis();
        long windowStart = now - WINDOW.toMillis();
        if (attempts.size() >= MAX_TRACKED_CLIENTS && !attempts.containsKey(client)) {
            evictIdle(windowStart);
            if (attempts.size() >= MAX_TRACKED_CLIENTS) {
                // Memória limitada: com muitos clientes ativos ao mesmo tempo, um cliente novo espera a janela.
                return WINDOW.toSeconds();
            }
        }
        Deque<Long> log = attempts.computeIfAbsent(client, key -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst();
            }
            if (log.size() >= MAX_ATTEMPTS) {
                long waitMillis = log.peekFirst() - windowStart;
                return Math.max(1, (waitMillis + 999) / 1000);
            }
            log.addLast(now);
            return 0;
        }
    }

    int trackedClients() {
        return attempts.size();
    }

    private void evictIdle(long windowStart) {
        attempts.entrySet().removeIf(entry -> {
            Deque<Long> log = entry.getValue();
            synchronized (log) {
                Long last = log.peekLast();
                return last == null || last <= windowStart;
            }
        });
    }
}
