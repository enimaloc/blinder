package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.link.Link;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Calcule l'état affiché dans le panel via les mêmes règles que la redirection réelle. */
@Component
public class LinkStatusResolver {

    private final AccessEvaluator evaluator;
    private final Clock clock;

    public LinkStatusResolver(AccessEvaluator evaluator, Clock clock) {
        this.evaluator = evaluator;
        this.clock = clock;
    }

    public LinkStatus statusOf(Link link) {
        VisitContext synthetic = new VisitContext("admin", "", false, "GET", Instant.now(clock));
        return switch (evaluator.evaluate(link, synthetic)) {
            case Allow a -> LinkStatus.ACTIVE;
            case Challenge c -> LinkStatus.ACTIVE;
            case Deny(DenyReason reason) -> switch (reason) {
                case DISABLED -> LinkStatus.DISABLED;
                case NOT_YET -> LinkStatus.SCHEDULED;
                case EXPIRED -> LinkStatus.EXPIRED;
                case EXHAUSTED -> LinkStatus.EXHAUSTED;
            };
        };
    }
}
