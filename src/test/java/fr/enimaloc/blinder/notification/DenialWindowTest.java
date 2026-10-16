package fr.enimaloc.blinder.notification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DenialWindowTest {

    static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");

    static class MutableClock extends Clock {
        Instant now = T0;

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
    final DenialWindow window = new DenialWindow(3, Duration.ofMinutes(10), clock, 1000);

    @Test
    void firesAtThresholdThenStartsOver() {
        UUID link = UUID.randomUUID();
        assertThat(window.record(link, T0)).isFalse();
        assertThat(window.record(link, T0.plusSeconds(1))).isFalse();
        assertThat(window.record(link, T0.plusSeconds(2))).isTrue();
        // La fenêtre a été vidée : il faut de nouveau 3 refus.
        assertThat(window.record(link, T0.plusSeconds(3))).isFalse();
        assertThat(window.record(link, T0.plusSeconds(4))).isFalse();
        assertThat(window.record(link, T0.plusSeconds(5))).isTrue();
    }

    @Test
    void forgetsDenialsOlderThanTheWindow() {
        UUID link = UUID.randomUUID();
        window.record(link, T0);
        window.record(link, T0.plusSeconds(1));
        assertThat(window.record(link, T0.plus(Duration.ofMinutes(11)))).isFalse();
        assertThat(window.record(link, T0.plus(Duration.ofMinutes(11)).plusSeconds(1))).isFalse();
        assertThat(window.record(link, T0.plus(Duration.ofMinutes(11)).plusSeconds(2))).isTrue();
    }

    @Test
    void countsEachLinkSeparately() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        window.record(a, T0);
        window.record(b, T0);
        window.record(a, T0.plusSeconds(1));
        assertThat(window.record(b, T0.plusSeconds(1))).isFalse();
        assertThat(window.record(a, T0.plusSeconds(2))).isTrue();
    }

    @Test
    void linksWhoseDenialsAllLeftTheWindowAreForgotten() {
        for (int i = 0; i < 50; i++) {
            window.record(UUID.randomUUID(), T0);
        }
        assertThat(window.trackedLinks()).isEqualTo(50);

        clock.now = T0.plus(Duration.ofMinutes(11));
        UUID recent = UUID.randomUUID();
        window.record(recent, clock.now);

        assertThat(window.trackedLinks()).isEqualTo(1);
    }

    @Test
    void entriesStillInsideTheWindowAreKept() {
        UUID a = UUID.randomUUID();
        window.record(a, T0);
        window.record(a, T0.plusSeconds(1));
        clock.now = T0.plus(Duration.ofMinutes(9));
        window.record(UUID.randomUUID(), clock.now);
        assertThat(window.trackedLinks()).isEqualTo(2);
        assertThat(window.record(a, clock.now)).isTrue();
    }

    @Test
    void theNumberOfTrackedLinksIsBounded() {
        DenialWindow bounded = new DenialWindow(3, Duration.ofMinutes(10), clock, 2);
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        bounded.record(oldest, T0);
        bounded.record(middle, T0.plusSeconds(1));
        bounded.record(middle, T0.plusSeconds(2));
        bounded.record(newest, T0.plusSeconds(3));

        assertThat(bounded.trackedLinks()).isEqualTo(2);
        // le lien évincé est celui dont le dernier refus est le plus ancien : « middle » est toujours compté
        assertThat(bounded.record(middle, T0.plusSeconds(4))).isTrue();
    }
}
