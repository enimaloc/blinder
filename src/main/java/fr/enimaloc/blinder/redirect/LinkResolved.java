package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.VisitContext;

import java.util.UUID;

public record LinkResolved(UUID linkId, VisitContext context, boolean counted) {
}
