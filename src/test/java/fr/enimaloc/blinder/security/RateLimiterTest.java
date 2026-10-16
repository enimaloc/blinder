package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");

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

    final MutableClock clock = new MutableClock();
    final RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(15), clock, 2);

    @Test
    void blocksAfterMaxFailuresWithinWindow() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isFalse();
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();
        assertThat(limiter.isBlocked("other")).isFalse();
    }

    @Test
    void windowSlides() {
        limiter.recordFailure("k");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();
        assertThat(limiter.retryAfter("k")).isEqualTo(Duration.ofMinutes(5));

        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(limiter.isBlocked("k")).isFalse();
        assertThat(limiter.retryAfter("k")).isEqualTo(Duration.ZERO);
    }

    @Test
    void resetClearsKey() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.reset("k");
        assertThat(limiter.isBlocked("k")).isFalse();
    }

    @Test
    void recordingWhileBlockedIsNoOp() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();
        Duration retryAfter1 = limiter.retryAfter("k");

        limiter.recordFailure("k");
        limiter.recordFailure("k");
        Duration retryAfter2 = limiter.retryAfter("k");

        // retryAfter doit rester constant (ne pas s'allonger)
        assertThat(retryAfter2).isEqualTo(retryAfter1);
    }

    @Test
    void blockedKeySurvivesFloodOfNonBlockedKeys() {
        // Bloquer une clé
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("blocked");
        }
        assertThat(limiter.isBlocked("blocked")).isTrue();

        // Envoyer un flot de clés non-bloquées
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("flood" + i);
        }

        // La clé bloquée doit survivre
        assertThat(limiter.isBlocked("blocked")).isTrue();
    }

    @Test
    void expiredKeyIsPurgedBeforeEviction() {
        // Enregistrer une clé avec un seul échec
        limiter.recordFailure("old");
        clock.now = clock.now.plus(Duration.ofMinutes(16)); // Sortir de la fenêtre

        // Bloquer une autre clé
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("blocked");
        }

        // Ajouter une troisième clé
        limiter.recordFailure("new");

        // L'ancienne clé devrait être purgée
        assertThat(limiter.isBlocked("old")).isFalse();
        assertThat(limiter.retryAfter("old")).isEqualTo(Duration.ZERO);
        // Les clés bloquées et nouvelles doivent exister
        assertThat(limiter.isBlocked("blocked")).isTrue();
    }

    @Test
    void whenEveryKeyIsBlockedNewKeyReportsBlocked() {
        // Bloquer les deux premières clés
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("b");
        }
        assertThat(limiter.isBlocked("a")).isTrue();
        assertThat(limiter.isBlocked("b")).isTrue();

        // Essayer d'ajouter une nouvelle clé : elle doit être refusée (et reportée comme bloquée)
        limiter.recordFailure("c");
        assertThat(limiter.isBlocked("c")).isTrue();
    }

    @Test
    void notBlockedHasZeroRetryAfter() {
        assertThat(limiter.retryAfter("none")).isEqualTo(Duration.ZERO);
    }
}
