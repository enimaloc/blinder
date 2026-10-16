package fr.enimaloc.blinder.access;

import java.time.Instant;
import java.util.UUID;

/**
 * @param submittedPassword mot de passe fourni (GET {@code pw} ou POST {@code password}), sinon {@code null}
 * @param deviceId          appareil déjà authentifié par son cookie pour ce domaine, sinon {@code null}
 */
public record VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now,
                           String submittedPassword, UUID deviceId) {

    public VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now) {
        this(clientIp, userAgent, bot, httpMethod, now, null, null);
    }

    /** Un GET ou un POST (formulaire de mot de passe) d'un client non-bot consomme un usage. */
    public boolean countable() {
        return ("GET".equals(httpMethod) || "POST".equals(httpMethod)) && !bot;
    }
}
