package fr.enimaloc.blinder.common;

import java.util.Optional;

/** Parse et formate les littéraux IPv4 et IPv6 sans DNS. */
public final class IpLiterals {

    private IpLiterals() {
    }

    /**
     * Parse une chaîne IP (IPv4 ou IPv6, ou IPv4-mapped).
     * @return Optional.of(4 bytes) pour IPv4, Optional.of(16 bytes) pour IPv6, Optional.empty() sinon.
     */
    public static Optional<byte[]> parse(String literal) {
        if (literal == null || literal.isEmpty()) {
            return Optional.empty();
        }

        // Tenter IPv4
        byte[] ipv4 = parseIpv4(literal);
        if (ipv4 != null) {
            return Optional.of(ipv4);
        }

        // Tenter IPv6
        byte[] ipv6 = parseIpv6(literal);
        if (ipv6 != null) {
            return Optional.of(ipv6);
        }

        return Optional.empty();
    }

    /**
     * Formate une adresse en texte canonique : IPv4 « a.b.c.d », IPv6 en hextets hexadécimaux minuscules sans zéros de tête, sans compression.
     */
    public static String format(byte[] address) {
        if (address.length == 4) {
            return String.format("%d.%d.%d.%d", address[0] & 0xFF, address[1] & 0xFF, address[2] & 0xFF, address[3] & 0xFF);
        } else if (address.length == 16) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) {
                    sb.append(":");
                }
                int value = ((address[i] & 0xFF) << 8) | (address[i + 1] & 0xFF);
                sb.append(String.format("%x", value));
            }
            return sb.toString();
        }
        return "";
    }

    private static byte[] parseIpv4(String s) {
        // IPv4 = exactly 4 decimal octets 0-255, no leading zeros, ASCII digits only
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }

        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                return null;
            }
            // Reject leading zeros (except "0" itself)
            if (part.length() > 1 && part.charAt(0) == '0') {
                return null;
            }
            // Must be ASCII digits only
            if (!part.matches("^[0-9]+$")) {
                return null;
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return null;
                }
                bytes[i] = (byte) value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private static byte[] parseIpv6(String s) {
        // IPv6 parsing with support for ::, hextets, and IPv4-mapped
        // Reject invalid patterns: no brackets, no zone id, no prefix
        if (s.contains("[") || s.contains("]") || s.contains("%") || s.contains("/")) {
            return null;
        }

        // Count :: (at most one)
        int doubleColonCount = 0;
        int doubleColonPos = -1;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == ':' && s.charAt(i + 1) == ':') {
                doubleColonCount++;
                if (doubleColonPos == -1) {
                    doubleColonPos = i;
                }
            }
        }

        if (doubleColonCount > 1) {
            return null; // More than one ::
        }

        // Reject ::: and other patterns
        if (s.contains(":::")) {
            return null;
        }

        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) {
            result[i] = 0;
        }

        byte[] parsed;
        if (doubleColonCount == 0) {
            // No :: compression - must have exactly 8 groups
            parsed = parseIpv6NoCompression(s, result);
        } else {
            // Has :: compression
            parsed = parseIpv6WithCompression(s, result, doubleColonPos);
        }

        if (parsed == null) {
            return null;
        }

        // Check if this is IPv4-mapped IPv6 (first 10 bytes are 0, next 2 are 0xffff)
        if (isIpv4Mapped(parsed)) {
            // Return just the last 4 bytes (the IPv4 address)
            byte[] ipv4 = new byte[4];
            ipv4[0] = parsed[12];
            ipv4[1] = parsed[13];
            ipv4[2] = parsed[14];
            ipv4[3] = parsed[15];
            return ipv4;
        }

        return parsed;
    }

    private static byte[] parseIpv6NoCompression(String s, byte[] result) {
        String[] parts = s.split(":", -1);

        // If last part is IPv4, we have 7 parts; otherwise 8
        boolean lastIsIpv4 = parts.length > 0 && parts[parts.length - 1].contains(".");
        int expectedParts = lastIsIpv4 ? 7 : 8;

        if (parts.length != expectedParts) {
            return null;
        }

        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].contains(".")) {
                // Last group may be IPv4
                byte[] ipv4 = parseIpv4(parts[i]);
                if (ipv4 == null) {
                    return null;
                }
                result[pos++] = ipv4[0];
                result[pos++] = ipv4[1];
                result[pos++] = ipv4[2];
                result[pos++] = ipv4[3];
            } else {
                if (!parseHextet(parts[i], result, pos)) {
                    return null;
                }
                pos += 2;
            }
        }
        return pos == 16 ? result : null;
    }

    private static byte[] parseIpv6WithCompression(String s, byte[] result, int doubleColonPos) {
        String before = s.substring(0, doubleColonPos);
        String after = s.substring(doubleColonPos + 2);

        String[] beforeParts = before.isEmpty() ? new String[0] : before.split(":", -1);
        String[] afterParts = after.isEmpty() ? new String[0] : after.split(":", -1);

        // Check for invalid patterns like ":::x" (would give empty beforeParts[0])
        if (beforeParts.length > 0 && beforeParts[0].isEmpty()) {
            if (beforeParts.length > 1) {
                return null; // Pattern like :::
            }
            beforeParts = new String[0];
        }
        if (afterParts.length > 0 && afterParts[afterParts.length - 1].isEmpty()) {
            if (afterParts.length > 1) {
                return null; // Pattern like x:::
            }
            afterParts = new String[0];
        }

        // Check if last afterPart is IPv4
        boolean afterHasIpv4 = afterParts.length > 0 && afterParts[afterParts.length - 1].contains(".");
        boolean beforeHasIpv4 = beforeParts.length > 0 && beforeParts[beforeParts.length - 1].contains(".");

        int beforeGroupCount = beforeParts.length;
        int afterGroupCount = afterParts.length;
        if (beforeHasIpv4) {
            beforeGroupCount--; // IPv4 counts as 2 groups, but we counted it as 1
            beforeGroupCount += 2;
        }
        if (afterHasIpv4) {
            afterGroupCount--; // IPv4 counts as 2 groups, but we counted it as 1
            afterGroupCount += 2;
        }

        int totalGroups = beforeGroupCount + afterGroupCount;
        if (totalGroups >= 8) {
            return null; // Not enough room for compression
        }

        int pos = 0;

        // Parse before parts
        for (int i = 0; i < beforeParts.length; i++) {
            if (i == beforeParts.length - 1 && beforeParts[i].contains(".")) {
                // Last before-part may be IPv4
                byte[] ipv4 = parseIpv4(beforeParts[i]);
                if (ipv4 == null) {
                    return null;
                }
                result[pos++] = ipv4[0];
                result[pos++] = ipv4[1];
                result[pos++] = ipv4[2];
                result[pos++] = ipv4[3];
            } else {
                if (!parseHextet(beforeParts[i], result, pos)) {
                    return null;
                }
                pos += 2;
            }
        }

        // Skip to end for after parts
        int afterBytesNeeded = afterGroupCount * 2;
        int afterStartPos = 16 - afterBytesNeeded;
        if (afterStartPos < pos) {
            return null; // Overlap
        }

        pos = afterStartPos;

        // Parse after parts
        for (int i = 0; i < afterParts.length; i++) {
            if (i == afterParts.length - 1 && afterParts[i].contains(".")) {
                // Last after-part may be IPv4
                byte[] ipv4 = parseIpv4(afterParts[i]);
                if (ipv4 == null) {
                    return null;
                }
                result[pos++] = ipv4[0];
                result[pos++] = ipv4[1];
                result[pos++] = ipv4[2];
                result[pos++] = ipv4[3];
            } else {
                if (!parseHextet(afterParts[i], result, pos)) {
                    return null;
                }
                pos += 2;
            }
        }

        return result;
    }

    private static boolean parseHextet(String hextet, byte[] result, int offset) {
        if (hextet.isEmpty() || hextet.length() > 4) {
            return false;
        }

        // Must be hex digits only
        if (!hextet.matches("^[0-9a-fA-F]+$")) {
            return false;
        }

        try {
            int value = Integer.parseInt(hextet, 16);
            result[offset] = (byte) ((value >> 8) & 0xFF);
            result[offset + 1] = (byte) (value & 0xFF);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16) {
            return false;
        }
        // Check first 10 bytes are 0 and next 2 are 0xffff
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF;
    }
}
