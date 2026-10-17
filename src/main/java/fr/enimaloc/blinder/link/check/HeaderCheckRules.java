package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.common.FieldErrorException;

import java.util.Optional;

/**
 * Validation et normalisation de l'en-tête attendu : nom = token HTTP (RFC 9110) ; valeur en ASCII visible (espace
 * compris, 0x20–0x7E), seule forme qu'un en-tête reçu peut reproduire à l'identique (le client HTTP décode les octets
 * en Latin-1 : une valeur accentuée ne correspondrait jamais et le lien refuserait tout le monde).
 */
public final class HeaderCheckRules {

    public static final int MAX_NAME = 100;
    public static final int MAX_VALUE = 500;

    public static final String NAME_FIELD = "headerName";
    public static final String VALUE_FIELD = "headerValue";

    private static final String TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~";

    private HeaderCheckRules() {
    }

    /** Nom et valeur rognés, prêts à être enregistrés. */
    public record Normalized(String name, String value) {
    }

    /**
     * @return vide si les deux champs sont vides (pas de contrôle)
     * @throws FieldErrorException un seul champ rempli, nom ou valeur invalide
     */
    public static Optional<Normalized> normalize(String rawName, String rawValue) {
        String name = rawName == null ? "" : rawName.strip();
        String value = rawValue == null ? "" : rawValue.strip();
        if (name.isEmpty() && value.isEmpty() && !hasInvalidValueChar(rawValue)) {
            return Optional.empty();
        }
        if (name.isEmpty()) {
            throw new FieldErrorException(NAME_FIELD, "Indiquez aussi l'en-tête attendu");
        }
        if (value.isEmpty()) {
            throw new FieldErrorException(VALUE_FIELD, "Indiquez aussi la valeur attendue");
        }
        if (name.length() > MAX_NAME || !name.chars().allMatch(HeaderCheckRules::isTokenChar)) {
            throw new FieldErrorException(NAME_FIELD, "Nom d'en-tête invalide");
        }
        // Contrôle sur la valeur brute : un CR/LF en bordure ne doit pas être effacé en silence par le rognage.
        if (value.length() > MAX_VALUE || hasInvalidValueChar(rawValue)) {
            throw new FieldErrorException(VALUE_FIELD, "Valeur invalide");
        }
        return Optional.of(new Normalized(name, value));
    }

    /** Tout caractère hors de l'ASCII visible et de l'espace (contrôles, CR/LF, tabulation, non-ASCII). */
    private static boolean hasInvalidValueChar(String value) {
        return value != null && value.chars().anyMatch(c -> c < 0x20 || c > 0x7E);
    }

    private static boolean isTokenChar(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || TOKEN_SYMBOLS.indexOf(c) >= 0;
    }
}
