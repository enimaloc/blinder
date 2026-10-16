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
        // Block a key at t0
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();
        Duration retryAfter1 = limiter.retryAfter("k");

        // Advance clock 5 min, record another failure
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        limiter.recordFailure("k");

        // retryAfter must stay constant (not grow to 10 min)
        Duration retryAfter2 = limiter.retryAfter("k");
        assertThat(retryAfter2).isEqualTo(Duration.ofMinutes(10)); // 15 - 5 = 10 min remaining
        assertThat(retryAfter2).isLessThan(retryAfter1);
    }

    @Test
    void blockedKeySurvivesFloodOfNonBlockedKeys() {
        // (a) Block one key
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("blocked");
        }
        assertThat(limiter.isBlocked("blocked")).isTrue();

        // Send a flood of non-blocked keys
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("flood" + i);
        }

        // Blocked key must survive
        assertThat(limiter.isBlocked("blocked")).isTrue();
    }

    @Test
    void expiredKeyIsPurgedBeforeEviction() {
        // (b) Fill table with all-blocked keys
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("b");
        }
        assertThat(limiter.isBlocked("a")).isTrue();
        assertThat(limiter.isBlocked("b")).isTrue();

        // Advance clock past window
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        // Record failure on new key → old keys purged, new is tracked and not blocked
        limiter.recordFailure("new");
        assertThat(limiter.isBlocked("new")).isFalse();
        assertThat(limiter.isBlocked("a")).isFalse();
        assertThat(limiter.isBlocked("b")).isFalse();
    }

    @Test
    void whenEveryKeyIsBlockedEvictsLruBlocked() {
        // (c) Fill table with all-blocked keys
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("b");
        }
        assertThat(limiter.isBlocked("a")).isTrue();
        assertThat(limiter.isBlocked("b")).isTrue();

        // Record failure on new key → evicts LRU (blocked) key
        limiter.recordFailure("c");
        assertThat(limiter.isBlocked("c")).isFalse();
        // "a" was the least recently used, so it should be evicted
        assertThat(limiter.isBlocked("a")).isFalse();
        assertThat(limiter.isBlocked("b")).isTrue();
    }

    @Test
    void notBlockedHasZeroRetryAfter() {
        assertThat(limiter.retryAfter("none")).isEqualTo(Duration.ZERO);
    }
}
