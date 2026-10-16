package fr.enimaloc.blinder.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Liste d'IP / plages CIDR (IPv4, IPv6). N'effectue jamais de résolution DNS. */
public final class IpAllowlist {

    public static final int MAX_ENTRIES = 50;

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final Pattern PREFIX = Pattern.compile("^\\d{1,3}$");
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
        for (String raw : input.trim().split("[,\\s]+")) {
            if (raw.isEmpty()) {
                continue;
            }
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
            if (!PREFIX.matcher(bits).matches()) {
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

    /** Analyse une IP littérale ; tout ce qui n'est pas strictement une IP est refusé avant InetAddress. */
    static Optional<byte[]> literal(String ip) {
        if (ip == null || ip.isEmpty()) {
            return Optional.empty();
        }
        Matcher v4 = IPV4.matcher(ip);
        if (v4.matches()) {
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int part = Integer.parseInt(v4.group(i + 1));
                if (part > 255) {
                    return Optional.empty();
                }
                bytes[i] = (byte) part;
            }
            return Optional.of(bytes);
        }
        if (ip.indexOf(':') < 0 || !IPV6_CHARS.matcher(ip).matches()) {
            return Optional.empty();
        }
        try {
            // Contient ':' : InetAddress l'analyse comme littéral IPv6 et échoue sans résolution DNS.
            // ::ffff:a.b.c.d est renvoyé comme Inet4Address (4 octets).
            return Optional.of(InetAddress.getByName(ip).getAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
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
            try {
                String host = InetAddress.getByAddress(network).getHostAddress();
                return prefix == network.length * 8 ? host : host + "/" + prefix;
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
