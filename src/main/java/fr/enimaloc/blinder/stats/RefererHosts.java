package fr.enimaloc.blinder.stats;

import java.net.URI;
import java.util.Locale;

/** Extrait le host d'un en-tête Referer. */
public final class RefererHosts {

    private RefererHosts() {
    }

    /** @return host en minuscules d'une URL http(s) valide, sinon {@code null} (jamais d'exception) */
    public static String hostOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(referer.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            return host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
