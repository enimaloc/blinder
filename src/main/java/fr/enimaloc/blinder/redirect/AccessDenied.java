package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.access.VisitContext;

import java.util.UUID;

public record AccessDenied(UUID linkId, DenyReason reason, VisitContext context) {
}
