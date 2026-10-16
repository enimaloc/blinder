package fr.enimaloc.blinder.access.rules;

import fr.enimaloc.blinder.access.AccessDecision;
import fr.enimaloc.blinder.access.AccessRule;
import fr.enimaloc.blinder.access.Allow;
import fr.enimaloc.blinder.access.Deny;
import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.access.VisitContext;
import fr.enimaloc.blinder.link.Link;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Pré-vérification ; la garde réelle sous concurrence est l'UPDATE atomique de {@code UsageCounter}. */
@Component
@Order(500)
public class MaxUsesRule implements AccessRule {

    @Override
    public AccessDecision evaluate(Link link, VisitContext context) {
        if (link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses()) {
            return new Deny(DenyReason.EXHAUSTED);
        }
        return new Allow();
    }
}
