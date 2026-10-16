package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.common.IpLiterals;
import java.util.UUID;

/** Normalisation des clés de limiteur de débit : IPv4 (et IPv4-mappé) → l'adresse ; IPv6 → le préfixe /64 ; valeur illisible → telle quelle. */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /** Normalise une adresse IP client : IPv4 ou IPv4-mappé → IPv4 ; IPv6 → préfixe /64. */
    public static String client(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return remoteAddr;
        }

        var parsed = IpLiterals.parse(remoteAddr);
        if (parsed.isEmpty()) {
            // Unparsable : return unchanged
            return remoteAddr;
        }

        byte[] bytes = parsed.get();
        if (bytes.length == 4) {
            // IPv4 or IPv4-mapped IPv6
            return IpLiterals.format(bytes);
        } else if (bytes.length == 16) {
            // IPv6 : return /64 prefix
            return formatIpv6Prefix(bytes);
        }

        return remoteAddr;
    }

    public static String linkPassword(UUID linkId, String remoteAddr) {
        return "link-pw:" + linkId + ":" + client(remoteAddr);
    }

    public static String linkPasswordAggregate(UUID linkId) {
        return "link-pw:" + linkId;
    }

    public static String adminLogin(String remoteAddr) {
        return "admin-login:" + client(remoteAddr);
    }

    private static String formatIpv6Prefix(byte[] bytes) {
        // IPv6 /64 : first 4 hextets (first 8 bytes)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append(":");
            }
            int value = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
            sb.append(String.format("%x", value));
        }
        sb.append("::/64");
        return sb.toString();
    }
}
