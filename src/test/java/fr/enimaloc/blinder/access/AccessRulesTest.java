package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.access.rules.EnabledRule;
import fr.enimaloc.blinder.access.rules.ExpiresAtRule;
import fr.enimaloc.blinder.access.rules.MaxUsesRule;
import fr.enimaloc.blinder.access.rules.NotBeforeRule;
import fr.enimaloc.blinder.access.rules.TtlAfterFirstUseRule;
import fr.enimaloc.blinder.link.Link;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccessRulesTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    static final VisitContext CTX = new VisitContext("203.0.113.1", "Mozilla/5.0", false, "GET", NOW);

    static Link link() {
        return new Link(null, "promo", "https://example.com", null);
    }

    @Test
    void enabledRule() {
        Link link = link();
        assertThat(new EnabledRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setEnabled(false);
        assertThat(new EnabledRule().evaluate(link, CTX)).isEqualTo(new Deny(DenyReason.DISABLED));
    }

    @Test
    void notBeforeRuleIsInclusiveAtTheBoundary() {
        Link link = link();
        assertThat(new NotBeforeRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setNotBefore(NOW);
        assertThat(new NotBeforeRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setNotBefore(NOW.plusSeconds(1));
        assertThat(new NotBeforeRule().evaluate(link, CTX)).isEqualTo(new Deny(DenyReason.NOT_YET));
    }

    @Test
    void expiresAtRuleIsExclusiveAtTheBoundary() {
        Link link = link();
        assertThat(new ExpiresAtRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setExpiresAt(NOW.plusSeconds(1));
        assertThat(new ExpiresAtRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setExpiresAt(NOW);
        assertThat(new ExpiresAtRule().evaluate(link, CTX)).isEqualTo(new Deny(DenyReason.EXPIRED));
    }

    @Test
    void ttlRuleStartsAtFirstUse() {
        Link link = link();
        link.setTtlAfterFirstUse(Duration.ofHours(1));
        assertThat(new TtlAfterFirstUseRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setFirstUsedAt(NOW.minus(Duration.ofMinutes(59)));
        assertThat(new TtlAfterFirstUseRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setFirstUsedAt(NOW.minus(Duration.ofHours(1)));
        assertThat(new TtlAfterFirstUseRule().evaluate(link, CTX)).isEqualTo(new Deny(DenyReason.EXPIRED));
    }

    @Test
    void ttlRuleIgnoresFirstUseWhenNoTtl() {
        Link link = link();
        link.setFirstUsedAt(NOW.minus(Duration.ofDays(365)));
        assertThat(new TtlAfterFirstUseRule().evaluate(link, CTX)).isEqualTo(new Allow());
    }

    @Test
    void maxUsesRule() {
        Link link = link();
        link.setUseCount(1000);
        assertThat(new MaxUsesRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setMaxUses(2);
        link.setUseCount(1);
        assertThat(new MaxUsesRule().evaluate(link, CTX)).isEqualTo(new Allow());
        link.setUseCount(2);
        assertThat(new MaxUsesRule().evaluate(link, CTX)).isEqualTo(new Deny(DenyReason.EXHAUSTED));
    }

    @Test
    void visitContextCountable() {
        assertThat(CTX.countable()).isTrue();
        assertThat(new VisitContext("ip", "ua", false, "HEAD", NOW).countable()).isFalse();
        assertThat(new VisitContext("ip", "ua", true, "GET", NOW).countable()).isFalse();
    }

    @Test
    void denyReasonGone() {
        assertThat(DenyReason.EXPIRED.gone()).isTrue();
        assertThat(DenyReason.EXHAUSTED.gone()).isTrue();
        assertThat(DenyReason.DISABLED.gone()).isFalse();
        assertThat(DenyReason.NOT_YET.gone()).isFalse();
    }
}
