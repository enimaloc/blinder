package fr.enimaloc.blinder.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DenialWindowTest {

    static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");

    final DenialWindow window = new DenialWindow(3, Duration.ofMinutes(10));

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
}
