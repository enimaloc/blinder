package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.VisitContext;

import java.util.UUID;

/** Tentative de mot de passe refusée par le limiteur (réponse 429). */
public record LinkThrottled(UUID linkId, VisitContext context) {
}
