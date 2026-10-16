package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.access.DenyReason;

import java.time.Instant;
import java.util.UUID;

/** Visite en attente d'écriture : jamais de mot de passe, le propriétaire est lu sur le lien à l'écriture. */
public record VisitRecord(UUID linkId, Instant occurredAt, VisitOutcome outcome, DenyReason denyReason,
                          String httpMethod, boolean bot, String ip, String userAgent, String referer) {
}
