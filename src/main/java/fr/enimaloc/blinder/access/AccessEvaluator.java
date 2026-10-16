package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.link.Link;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccessEvaluator {

    private final List<AccessRule> rules;

    /** Spring injecte la liste triée selon {@code @Order}. */
    public AccessEvaluator(List<AccessRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public AccessDecision evaluate(Link link, VisitContext context) {
        for (AccessRule rule : rules) {
            AccessDecision decision = rule.evaluate(link, context);
            if (!(decision instanceof Allow)) {
                return decision;
            }
        }
        return new Allow();
    }
}
