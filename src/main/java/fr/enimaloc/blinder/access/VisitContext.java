package fr.enimaloc.blinder.access;

import java.time.Instant;

public record VisitContext(String clientIp, String userAgent, boolean bot, String httpMethod, Instant now) {

    /** Seul un GET d'un client non-bot consomme un usage. */
    public boolean countable() {
        return "GET".equals(httpMethod) && !bot;
    }
}
