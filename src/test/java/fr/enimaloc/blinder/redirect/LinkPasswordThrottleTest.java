package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.security.RateLimitKeys;
import fr.enimaloc.blinder.security.RateLimiter;
import fr.enimaloc.blinder.security.RateLimiters;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LinkPasswordThrottleTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    final RateLimiter perClient = new RateLimiter(5, Duration.ofMinutes(15), clock, 100);
    final RateLimiter aggregate = new RateLimiter(7, Duration.ofMinutes(15), clock, 100);
    final LinkPasswordThrottle throttle =
            new LinkPasswordThrottle(new RateLimiters(perClient, aggregate,
                    new RateLimiter(5, Duration.ofMinutes(15), clock, 100),
                    new RateLimiter(20, Duration.ofMinutes(15), clock, 100)));
    final UUID link = UUID.randomUUID();

    @Test
    void sixthReservationFromTheSameClientIsRefused() {
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.tryReserve(link, "198.51.100.7")).isEmpty();
        }
        assertThat(throttle.tryReserve(link, "198.51.100.7")).hasValue(Duration.ofMinutes(15));
        assertThat(throttle.tryReserve(link, "198.51.100.8")).isEmpty();
    }

    @Test
    void aggregateRefusalReleasesTheClientReservation() {
        for (int i = 1; i <= 7; i++) {
            assertThat(throttle.tryReserve(link, "198.51.100." + i)).isEmpty();
        }
        assertThat(throttle.tryReserve(link, "203.0.113.1")).isPresent();
        assertThat(perClient.isBlocked(RateLimitKeys.linkPassword(link, "203.0.113.1"))).isFalse();
        // the refused client holds no reservation: after an aggregate release it can reserve again
        throttle.succeeded(link, "198.51.100.1");
        assertThat(throttle.tryReserve(link, "203.0.113.1")).isEmpty();
    }

    @Test
    void succeededResetsClientAndReleasesAggregate() {
        for (int i = 0; i < 4; i++) {
            throttle.tryReserve(link, "198.51.100.7");
        }
        assertThat(throttle.tryReserve(link, "198.51.100.7")).isEmpty();
        throttle.succeeded(link, "198.51.100.7");
        assertThat(perClient.isBlocked(RateLimitKeys.linkPassword(link, "198.51.100.7"))).isFalse();
        // aggregate held 5, one released -> 4 left, so exactly 3 more reservations fit under 7
        assertThat(throttle.tryReserve(link, "198.51.100.20")).isEmpty();
        assertThat(throttle.tryReserve(link, "198.51.100.21")).isEmpty();
        assertThat(throttle.tryReserve(link, "198.51.100.22")).isEmpty();
        assertThat(throttle.tryReserve(link, "198.51.100.23")).isPresent();
    }

    @Test
    void abandonedReleasesBoth() {
        for (int i = 0; i < 4; i++) {
            throttle.tryReserve(link, "198.51.100.7");
        }
        throttle.tryReserve(link, "198.51.100.7");
        throttle.abandoned(link, "198.51.100.7");
        assertThat(perClient.isBlocked(RateLimitKeys.linkPassword(link, "198.51.100.7"))).isFalse();
        assertThat(aggregate.isBlocked(RateLimitKeys.linkPasswordAggregate(link))).isFalse();
        throttle.rejected(link, "198.51.100.7");
        assertThat(perClient.isBlocked(RateLimitKeys.linkPassword(link, "198.51.100.7"))).isFalse();
    }
}
