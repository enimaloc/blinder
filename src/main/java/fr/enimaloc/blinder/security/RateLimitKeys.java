package fr.enimaloc.blinder.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
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

        // Vérifier si ça ressemble à une adresse IPv6 (contient ':')
        if (remoteAddr.contains(":")) {
            // Vérifier que c'est seulement du hex, ":" et "." (IPv4-mapped)
            if (!remoteAddr.matches("^[0-9a-fA-F:.]+$")) {
                // Valeur illisible : retourner telle quelle
                return remoteAddr;
            }

            // Vérifier si c'est une adresse IPv4-mapped (contient ::ffff:)
            if (remoteAddr.toLowerCase().contains("::ffff:")) {
                // Extraire la partie IPv4 (après ::ffff:)
                int idx = remoteAddr.toLowerCase().indexOf("::ffff:");
                String ipv4Part = remoteAddr.substring(idx + 7); // 7 = "::ffff:".length()
                return ipv4Part;
            }

            // Essayer de parser comme adresse IPv6
            try {
                InetAddress addr = InetAddress.getByName(remoteAddr);
                byte[] bytes = addr.getAddress();

                if (bytes.length == 4) {
                    // Cette branche ne devrait normalement pas être atteinte pour les vraies IPv6,
                    // mais on la garde par sécurité
                    return remoteAddr;
                } else if (bytes.length == 16) {
                    // IPv6 pur : retourner le préfixe /64
                    return formatIpv6Prefix(bytes);
                }
            } catch (UnknownHostException e) {
                // Impossible à parser : retourner telle quelle
                return remoteAddr;
            }
        }

        // IPv4 ou valeur illisible
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

    public static final String ADMIN_LOGIN_AGGREGATE = "admin-login";

    private static String formatIpv6Prefix(byte[] bytes) {
        // IPv6 /64 : les 8 premiers octets
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(String.format("%02x%02x", bytes[i * 2] & 0xFF, bytes[i * 2 + 1] & 0xFF));
        }
        sb.append("::");
        return sb.toString();
    }
}
