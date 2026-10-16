package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.VisitContext;

import java.util.UUID;

/** Mot de passe demandé (rejected=false) ou refusé (rejected=true). */
public record LinkChallenged(UUID linkId, VisitContext context, boolean rejected) {
}
