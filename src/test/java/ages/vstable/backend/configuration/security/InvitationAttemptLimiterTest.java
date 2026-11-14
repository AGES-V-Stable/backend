package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationAttemptLimiterTest {

    private MutableClock clock;
    private InvitationAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-12T15:00:00Z"));
        limiter = new InvitationAttemptLimiter(clock);
    }

    @Test
    void tryAcquire_upToThirtyAttemptsInAMinute_areAllowed() {
        // Act / Assert
        for (int i = 0; i < InvitationAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
        }
    }

    @Test
    void tryAcquire_thirtyFirstAttemptInTheWindow_isRefusedWithTheSecondsUntilTheOldestExpires() {
        // Arrange: 30 tentativas, a primeira 20 s antes das demais
        limiter.tryAcquire("10.0.0.1");
        clock.advance(Duration.ofSeconds(20));
        for (int i = 1; i < InvitationAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.tryAcquire("10.0.0.1");
        }

        // Act
        long retryAfter = limiter.tryAcquire("10.0.0.1");

        // Assert: a mais antiga sai da janela em 40 s
        assertThat(retryAfter).isEqualTo(40);
    }

    @Test
    void tryAcquire_refusedAttemptsDoNotCountAndTheWindowSlides() {
        // Arrange
        exhaust("10.0.0.1");
        assertThat(limiter.tryAcquire("10.0.0.1")).isPositive();

        // Act
        clock.advance(Duration.ofSeconds(60));

        // Assert
        assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
    }

    @Test
    void tryAcquire_retryAfterIsAtLeastOneSecond() {
        // Arrange
        exhaust("10.0.0.1");
        clock.advance(Duration.ofMillis(59_999));

        // Act / Assert
        assertThat(limiter.tryAcquire("10.0.0.1")).isEqualTo(1);
    }

    @Test
    void tryAcquire_eachClientHasItsOwnLimit() {
        // Arrange
        exhaust("10.0.0.1");

        // Act / Assert
        assertThat(limiter.tryAcquire("10.0.0.1")).isPositive();
        assertThat(limiter.tryAcquire("10.0.0.2")).isZero();
    }

    @Test
    void tryAcquire_manyTrackedClients_discardsTheIdleOnes() {
        // Arrange
        for (int i = 0; i <= InvitationAttemptLimiter.MAX_TRACKED_CLIENTS; i++) {
            limiter.tryAcquire("idle-" + i);
        }
        clock.advance(Duration.ofMinutes(2));

        // Act
        limiter.tryAcquire("active");

        // Assert
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    void tryAcquire_trackingLimitReachedWithActiveClients_refusesNewClientsButKeepsServingKnownOnes() {
        // Arrange: o teto de clientes, todos com tentativa recente (por exemplo, rotação de IPv6)
        for (int i = 0; i < InvitationAttemptLimiter.MAX_TRACKED_CLIENTS; i++) {
            limiter.tryAcquire("client-" + i);
        }

        // Act / Assert: a memória não cresce além do teto
        assertThat(limiter.tryAcquire("new-client")).isEqualTo(InvitationAttemptLimiter.WINDOW.toSeconds());
        assertThat(limiter.trackedClients()).isEqualTo(InvitationAttemptLimiter.MAX_TRACKED_CLIENTS);
        assertThat(limiter.tryAcquire("client-0")).isZero();
    }

    private void exhaust(String client) {
        for (int i = 0; i < InvitationAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.tryAcquire(client);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
