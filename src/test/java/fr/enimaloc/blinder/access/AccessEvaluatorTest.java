package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.link.Link;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessEvaluatorTest {

    final VisitContext ctx = new VisitContext("ip", "ua", false, "GET", Instant.EPOCH);
    final Link link = new Link(null, "x", "https://example.com", null);

    @Test
    void allowsWhenEveryRuleAllows() {
        AccessEvaluator evaluator = new AccessEvaluator(List.of((l, c) -> new Allow(), (l, c) -> new Allow()));
        assertThat(evaluator.evaluate(link, ctx)).isEqualTo(new Allow());
    }

    @Test
    void firstNonAllowWinsAndStopsTheChain() {
        List<String> called = new ArrayList<>();
        AccessEvaluator evaluator = new AccessEvaluator(List.of(
                (l, c) -> { called.add("a"); return new Allow(); },
                (l, c) -> { called.add("b"); return new Deny(DenyReason.EXPIRED); },
                (l, c) -> { called.add("c"); return new Deny(DenyReason.DISABLED); }));
        assertThat(evaluator.evaluate(link, ctx)).isEqualTo(new Deny(DenyReason.EXPIRED));
        assertThat(called).containsExactly("a", "b");
    }
}
