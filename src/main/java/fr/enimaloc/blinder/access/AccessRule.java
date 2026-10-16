package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.link.Link;

/** Une contrainte d'accès. Les implémentations sont des beans ordonnés par {@code @Order}. */
@FunctionalInterface
public interface AccessRule {

    AccessDecision evaluate(Link link, VisitContext context);
}
