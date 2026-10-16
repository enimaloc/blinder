package fr.enimaloc.blinder.common;

import java.util.Locale;
import java.util.regex.Pattern;

public final class HostNormalizer {

    private static final Pattern VALID = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");

    private HostNormalizer() {
    }

    /** Minuscules, sans port, sans point final. Jamais {@code null}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(0, end + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    /** Valide un nom de domaine déjà normalisé (pas d'IP v6, pas de port). */
    public static boolean isValid(String normalized) {
        return normalized != null && VALID.matcher(normalized).matches();
    }
}
