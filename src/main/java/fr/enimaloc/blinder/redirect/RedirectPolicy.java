package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.link.Link;
import org.springframework.http.HttpStatus;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Statut HTTP effectif et URL cible (paramètres transmis) d'une redirection autorisée. */
public final class RedirectPolicy {

    private static final Set<String> NEVER_FORWARDED = Set.of("pw", "password", "_csrf");

    private RedirectPolicy() {
    }

    /** Lien sans limite, date ni protection : seul cas où une redirection permanente est acceptable. */
    public static boolean isBare(Link link) {
        return link.getMaxUses() == null
                && link.getTtlAfterFirstUse() == null
                && link.getNotBefore() == null
                && link.getExpiresAt() == null
                && link.getPasswordHash() == null
                && (link.getIpAllowlist() == null || link.getIpAllowlist().isBlank())
                && !link.isDeviceProtected();
    }

    /**
     * POST (formulaires mot de passe / page intermédiaire) → toujours 303, pour que le navigateur ne rejoue jamais
     * le corps vers la cible. Sinon : 301/308 seulement si le lien est nu (repli défensif en 302) ; 307 toujours ;
     * tout le reste → 302.
     */
    public static HttpStatus effectiveStatus(Link link, String method) {
        if ("POST".equalsIgnoreCase(method)) {
            return HttpStatus.SEE_OTHER;
        }
        return switch (link.getRedirectStatus()) {
            case 307 -> HttpStatus.TEMPORARY_REDIRECT;
            case 301 -> isBare(link) ? HttpStatus.MOVED_PERMANENTLY : HttpStatus.FOUND;
            case 308 -> isBare(link) ? HttpStatus.PERMANENT_REDIRECT : HttpStatus.FOUND;
            default -> HttpStatus.FOUND;
        };
    }

    /** URL cible, enrichie des paramètres de la requête si l'option est active (la cible gagne). */
    public static String target(Link link, Map<String, String[]> requestParams) {
        String target = link.getTargetUrl();
        if (!link.isForwardQuery() || requestParams == null || requestParams.isEmpty()) {
            return target;
        }
        int hash = target.indexOf('#');
        String base = hash < 0 ? target : target.substring(0, hash);
        String fragment = hash < 0 ? "" : target.substring(hash);
        Set<String> existing = parameterNames(base);
        StringBuilder out = new StringBuilder(base);
        boolean hasQuery = base.indexOf('?') >= 0;
        for (Map.Entry<String, String[]> entry : requestParams.entrySet()) {
            String name = entry.getKey();
            if (NEVER_FORWARDED.contains(name) || existing.contains(name)) {
                continue;
            }
            for (String value : entry.getValue()) {
                out.append(hasQuery ? '&' : '?');
                hasQuery = true;
                out.append(encode(name)).append('=').append(encode(value == null ? "" : value));
            }
        }
        return out.append(fragment).toString();
    }

    private static Set<String> parameterNames(String base) {
        int question = base.indexOf('?');
        if (question < 0) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (String pair : base.substring(question + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String raw = equals < 0 ? pair : pair.substring(0, equals);
            try {
                names.add(URLDecoder.decode(raw, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                names.add(raw);
            }
        }
        return names;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
