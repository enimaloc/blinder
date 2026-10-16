package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.access.rules.EnabledRule;
import fr.enimaloc.blinder.access.rules.ExpiresAtRule;
import fr.enimaloc.blinder.access.rules.MaxUsesRule;
import fr.enimaloc.blinder.access.rules.NotBeforeRule;
import fr.enimaloc.blinder.access.rules.TtlAfterFirstUseRule;
import fr.enimaloc.blinder.link.Link;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkStatusResolverTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    final LinkStatusResolver resolver = new LinkStatusResolver(
            new AccessEvaluator(List.of(new EnabledRule(), new NotBeforeRule(), new ExpiresAtRule(),
                    new TtlAfterFirstUseRule(), new MaxUsesRule())),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Link link() {
        return new Link(null, "x", "https://example.com", null);
    }

    @Test
    void mapsEveryDecision() {
        Link active = link();
        assertThat(resolver.statusOf(active)).isEqualTo(LinkStatus.ACTIVE);

        Link disabled = link();
        disabled.setEnabled(false);
        assertThat(resolver.statusOf(disabled)).isEqualTo(LinkStatus.DISABLED);

        Link scheduled = link();
        scheduled.setNotBefore(NOW.plusSeconds(60));
        assertThat(resolver.statusOf(scheduled)).isEqualTo(LinkStatus.SCHEDULED);

        Link expired = link();
        expired.setExpiresAt(NOW.minusSeconds(60));
        assertThat(resolver.statusOf(expired)).isEqualTo(LinkStatus.EXPIRED);

        Link exhausted = link();
        exhausted.setMaxUses(1);
        exhausted.setUseCount(1);
        assertThat(resolver.statusOf(exhausted)).isEqualTo(LinkStatus.EXHAUSTED);
    }
}
