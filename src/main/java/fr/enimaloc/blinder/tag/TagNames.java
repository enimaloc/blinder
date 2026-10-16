package fr.enimaloc.blinder.tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Règles de nommage et de couleur des tags. */
public final class TagNames {

    public static final int MAX_LENGTH = 40;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private TagNames() {
    }

    /** Espaces rognés et réduits à un seul. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /** @return le message d'erreur, ou vide si le nom (déjà normalisé ou non) est valide */
    public static Optional<String> validate(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return Optional.of("Nom requis");
        }
        if (normalized.indexOf(',') >= 0) {
            return Optional.of("Virgule interdite");
        }
        // La clé de comparaison (minuscules) peut être plus longue que le nom : elle tient aussi dans 40 caractères.
        if (normalized.length() > MAX_LENGTH || normalized.toLowerCase(Locale.ROOT).length() > MAX_LENGTH) {
            return Optional.of("40 caractères maximum");
        }
        return Optional.empty();
    }

    /** Sépare sur la virgule, normalise, ignore les vides et dédoublonne sans casse (première graphie gardée). */
    public static List<String> parseList(String csv) {
        if (csv == null) {
            return List.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String part : csv.split(",")) {
            String name = normalize(part);
            if (!name.isEmpty()) {
                byKey.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** @return {@code #rrggbb} en minuscules, ou {@code null} si vide
     * @throws IllegalArgumentException si la valeur n'est pas une couleur hexadécimale */
    public static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String color = raw.trim();
        if (!COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("Couleur invalide");
        }
        return color.toLowerCase(Locale.ROOT);
    }
}
