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

@Component
@Order(100)
public class EnabledRule implements AccessRule {

    @Override
    public AccessDecision evaluate(Link link, VisitContext context) {
        return link.isEnabled() ? new Allow() : new Deny(DenyReason.DISABLED);
    }
}
