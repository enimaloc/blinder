package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.stats.Visit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Charge utile JSON d'un envoi. Jamais l'URL cible ni un mot de passe : seule l'URL courte publique figure. */
@Component
@RequiredArgsConstructor
public class NotificationPayloads {

    /** Chiffres propres à l'événement : seuil de visites, ou nombre de refus et durée de la fenêtre. */
    public record Details(Integer count, Long windowMinutes) {
        public static final Details NONE = new Details(null, null);
    }

    private static final int MAX_FREE_TEXT = 300;

    private final JsonMapper mapper;

    /** {@code link.domain} doit être chargé (appel dans une transaction). {@code link} et {@code visit} peuvent être nuls. */
    public String json(TriggerType event, Instant occurredAt, Link link, Visit visit) {
        return json(event, occurredAt, link, visit, Details.NONE);
    }

    public String json(TriggerType event, Instant occurredAt, Link link, Visit visit, Details details) {
        String text = text(event, link, visit, details);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("event", event.name());
        root.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(visit != null ? visit.getOccurredAt() : occurredAt));
        root.put("link", link == null ? null : linkNode(link));
        root.put("visit", visit == null ? null : visitNode(visit));
        root.put("text", text);
        root.put("content", text);
        return mapper.writeValueAsString(root);
    }

    public static String label(Link link) {
        return link.getDomain().getHost() + "/" + link.getSlug();
    }

    private static Map<String, Object> linkNode(Link link) {
        String host = link.getDomain().getHost();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", link.getId().toString());
        node.put("domain", host);
        node.put("slug", link.getSlug());
        node.put("url", "https://" + host + "/" + link.getSlug());
        return node;
    }

    private static Map<String, Object> visitNode(Visit visit) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("outcome", visit.getOutcome().name());
        node.put("reason", visit.getDenyReason() == null ? null : visit.getDenyReason().name());
        node.put("ip", visit.getIp());
        node.put("country", visit.getCountry());
        node.put("city", visit.getCity());
        node.put("browser", visit.getBrowser());
        node.put("os", visit.getOs());
        node.put("referer", truncate(visit.getReferer()));
        node.put("userAgent", truncate(visit.getUserAgent()));
        return node;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_FREE_TEXT ? value : value.substring(0, MAX_FREE_TEXT);
    }

    private static String text(TriggerType event, Link link, Visit visit, Details details) {
        if (event == TriggerType.TEST || link == null) {
            return "Test de notification Blinder";
        }
        String label = label(link);
        return switch (event) {
            case VISIT -> "Visite de " + label + " depuis " + (visit == null || visit.getIp() == null ? "?" : visit.getIp())
                    + " (" + (visit == null || visit.getCountry() == null ? "pays inconnu" : visit.getCountry()) + ")";
            case THRESHOLD -> label + " a atteint "
                    + (details.count() != null ? details.count() : link.getUseCount()) + " visites";
            case EXHAUSTED -> label + " est épuisé";
            case EXPIRED -> label + " a expiré";
            case DENIALS -> "Refus répétés sur " + label + " (" + details.count() + " en " + details.windowMinutes() + " min)";
            case TEST -> "Test de notification Blinder";
        };
    }
}
