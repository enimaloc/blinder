package fr.enimaloc.blinder.common;

import java.util.Optional;

/** Parse et formate les littéraux IPv4 et IPv6 sans DNS (analyseur écrit à la main, jamais InetAddress). */
public final class IpLiterals {

    private IpLiterals() {
    }

    /**
     * Parse un littéral IPv4 ou IPv6.
     *
     * @return 4 octets pour IPv4 et IPv4-mapped, 16 octets pour les autres IPv6, vide si invalide
     */
    public static Optional<byte[]> parse(String literal) {
        if (literal == null || literal.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = literal.indexOf(':') >= 0 ? parseIpv6(literal) : parseIpv4(literal);
        return Optional.ofNullable(bytes);
    }

    /** IPv4 « a.b.c.d » ; IPv6 en hextets hexadécimaux minuscules sans zéros de tête, sans compression. */
    public static String format(byte[] address) {
        StringBuilder sb = new StringBuilder();
        if (address.length == 4) {
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(address[i] & 0xFF);
            }
        } else if (address.length == 16) {
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(Integer.toHexString(((address[i] & 0xFF) << 8) | (address[i + 1] & 0xFF)));
            }
        } else {
            throw new IllegalArgumentException("Adresse de " + address.length + " octets");
        }
        return sb.toString();
    }

    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int value = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String s) {
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) {
            return null; // ":::" ou plus d'un "::"
        }
        String left = dc >= 0 ? s.substring(0, dc) : s;
        String right = dc >= 0 ? s.substring(dc + 2) : null;

        int[] groups = new int[8];
        int[] leftGroups = parseGroups(left, right == null);
        if (leftGroups == null) {
            return null;
        }
        int[] rightGroups = right == null ? new int[0] : parseGroups(right, true);
        if (rightGroups == null) {
            return null;
        }
        int total = leftGroups.length + rightGroups.length;
        if (right == null ? total != 8 : total > 7) {
            return null;
        }
        System.arraycopy(leftGroups, 0, groups, 0, leftGroups.length);
        System.arraycopy(rightGroups, 0, groups, 8 - rightGroups.length, rightGroups.length);

        byte[] result = new byte[16];
        for (int i = 0; i < 8; i++) {
            result[2 * i] = (byte) (groups[i] >> 8);
            result[2 * i + 1] = (byte) groups[i];
        }
        if (isIpv4Mapped(result)) {
            return new byte[] {result[12], result[13], result[14], result[15]};
        }
        return result;
    }

    /** Hextets séparés par ':' ; un IPv4 pointé n'est admis qu'en dernier groupe si {@code lastMayBeIpv4}. */
    private static int[] parseGroups(String part, boolean lastMayBeIpv4) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] tokens = part.split(":", -1);
        int[] out = new int[tokens.length + 1];
        int n = 0;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.indexOf('.') >= 0) {
                if (!lastMayBeIpv4 || i != tokens.length - 1) {
                    return null;
                }
                byte[] v4 = parseIpv4(t);
                if (v4 == null) {
                    return null;
                }
                out[n++] = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
                out[n++] = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
            } else {
                int h = parseHextet(t);
                if (h < 0) {
                    return null;
                }
                out[n++] = h;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static int parseHextet(String t) {
        if (t.isEmpty() || t.length() > 4) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < t.length(); i++) {
            int d = Character.digit(t.charAt(i), 16);
            char c = t.charAt(i);
            if (d < 0 || c > 'f') { // refuse chiffres Unicode et pleine largeur
                return -1;
            }
            value = value * 16 + d;
        }
        return value;
    }

    private static boolean isIpv4Mapped(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF;
    }
}
