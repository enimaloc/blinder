package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.common.IpLiterals;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;

/** Liste d'IP / plages CIDR (IPv4, IPv6). N'effectue jamais de résolution DNS. */
public final class IpAllowlist {

    public static final int MAX_ENTRIES = 50;

    private static final IpAllowlist EMPTY = new IpAllowlist(List.of());

    private final List<Range> ranges;

    private IpAllowlist(List<Range> ranges) {
        this.ranges = ranges;
    }

    /** Lit la forme stockée ; une entrée illisible est ignorée (elle n'autorise rien). */
    public static IpAllowlist parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return EMPTY;
        }
        List<Range> ranges = new ArrayList<>();
        for (String entry : stored.split(",")) {
            parseEntry(entry.trim()).ifPresent(ranges::add);
        }
        return ranges.isEmpty() ? EMPTY : new IpAllowlist(List.copyOf(ranges));
    }

    /**
     * Valide la saisie du panel (séparateurs : retours à la ligne, virgules, espaces).
     *
     * @return la forme stockée, ou {@code null} si la saisie est vide
     * @throws IllegalArgumentException entrée invalide ou plus de {@value #MAX_ENTRIES} entrées
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Set<String> canonical = new LinkedHashSet<>();
        StringTokenizer tokens = new StringTokenizer(input, ", \t\n\r\f");
        while (tokens.hasMoreTokens()) {
            String raw = tokens.nextToken();
            Range range = parseEntry(raw)
                    .orElseThrow(() -> new IllegalArgumentException("Entrée invalide : " + raw));
            canonical.add(range.canonical());
        }
        if (canonical.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(MAX_ENTRIES + " entrées maximum");
        }
        return canonical.isEmpty() ? null : String.join(",", canonical);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean matches(String clientIp) {
        Optional<byte[]> address = literal(clientIp);
        return address.isPresent() && ranges.stream().anyMatch(range -> range.contains(address.get()));
    }

    static Optional<Range> parseEntry(String entry) {
        if (entry == null || entry.isEmpty()) {
            return Optional.empty();
        }
        String ip = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            ip = entry.substring(0, slash);
            String bits = entry.substring(slash + 1);
            if (!isPrefixDigits(bits)) {
                return Optional.empty();
            }
            prefix = Integer.parseInt(bits);
        }
        Optional<byte[]> address = literal(ip);
        if (address.isEmpty()) {
            return Optional.empty();
        }
        int max = address.get().length * 8;
        int bits = prefix == null ? max : prefix;
        if (bits > max) {
            return Optional.empty();
        }
        return Optional.of(new Range(address.get(), bits));
    }

    private static boolean isPrefixDigits(String bits) {
        if (bits.isEmpty() || bits.length() > 3) {
            return false;
        }
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) < '0' || bits.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Analyse une IP littérale via IpLiterals (pas de résolution DNS). */
    static Optional<byte[]> literal(String ip) {
        return IpLiterals.parse(ip);
    }

    record Range(byte[] network, int prefix) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        String canonical() {
            String host = IpLiterals.format(network);
            return prefix == network.length * 8 ? host : host + "/" + prefix;
        }
    }
}
