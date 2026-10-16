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
    void evictsLeastRecentlyUsedKeyWhenFull() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        limiter.recordFailure("b");
        limiter.recordFailure("c");
        assertThat(limiter.isBlocked("a")).isFalse();
    }

    @Test
    void notBlockedHasZeroRetryAfter() {
        assertThat(limiter.retryAfter("none")).isEqualTo(Duration.ZERO);
    }
}
