package fr.enimaloc.blinder.access;

import java.time.Instant;
import java.util.UUID;

/**
 * @param submittedPassword mot de passe fourni (GET {@code pw} ou POST {@code password}), sinon {@code null}
 * @param deviceId          appareil déjà authentifié par son cookie pour ce domaine, sinon {@code null}
 * @param referer           en-tête {@code Referer} (tronqué à 1024 caractères), sinon {@code null}
 */
public record VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now,
                           String submittedPassword, UUID deviceId, String referer) {

    public VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now,
                        String submittedPassword, UUID deviceId) {
        this(clientIp, userAgent, bot, httpMethod, now, submittedPassword, deviceId, null);
    }

    public VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now) {
        this(clientIp, userAgent, bot, httpMethod, now, null, null);
    }

    /** Un GET ou un POST (formulaire de mot de passe) d'un client non-bot consomme un usage. */
    public boolean countable() {
        return ("GET".equals(httpMethod) || "POST".equals(httpMethod)) && !bot;
    }

    /** Le mot de passe soumis ne doit jamais apparaître dans les logs ni les événements. */
    @Override
    public String toString() {
        return "VisitContext[clientIp=" + clientIp + ", userAgent=" + userAgent + ", bot=" + bot
                + ", httpMethod=" + httpMethod + ", now=" + now
                + ", submittedPassword=" + (submittedPassword == null ? "null" : "***")
                + ", deviceId=" + deviceId + ", referer=" + referer + "]";
    }
}
