package ages.vstable.backend.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Limite de gerações de link de um convite: no mínimo 60 segundos entre duas gerações e no máximo cinco
 * por hora, em janela móvel, contando a criação como a primeira geração.
 */
final class InvitationRegenerationLimit {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(60);
    static final Duration WINDOW = Duration.ofHours(1);
    static final int MAX_PER_WINDOW = 5;

    private InvitationRegenerationLimit() {
    }

    /**
     * Segundos até uma nova geração ser permitida pelos dois limites ao mesmo tempo, ou 0 se já é permitida.
     * {@code generationsInWindow} são as gerações ocorridas depois de {@code now - WINDOW}.
     */
    static long retryAfterSeconds(List<OffsetDateTime> generationsInWindow, OffsetDateTime now) {
        if (generationsInWindow.isEmpty()) {
            return 0;
        }
        List<OffsetDateTime> sorted = generationsInWindow.stream().sorted().toList();
        OffsetDateTime allowedAt = sorted.get(sorted.size() - 1).plus(MIN_INTERVAL);
        if (sorted.size() >= MAX_PER_WINDOW) {
            OffsetDateTime windowOpensAt = sorted.get(sorted.size() - MAX_PER_WINDOW).plus(WINDOW);
            if (windowOpensAt.isAfter(allowedAt)) {
                allowedAt = windowOpensAt;
            }
        }
        if (!allowedAt.isAfter(now)) {
            return 0;
        }
        long millis = Duration.between(now, allowedAt).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
