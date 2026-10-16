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
@Order(300)
public class ExpiresAtRule implements AccessRule {

    @Override
    public AccessDecision evaluate(Link link, VisitContext context) {
        if (link.getExpiresAt() != null && !context.now().isBefore(link.getExpiresAt())) {
            return new Deny(DenyReason.EXPIRED);
        }
        return new Allow();
    }
}
