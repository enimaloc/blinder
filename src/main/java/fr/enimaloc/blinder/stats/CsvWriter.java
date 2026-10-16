package fr.enimaloc.blinder.stats;

import java.io.IOException;
import java.io.Writer;

/** Écriture CSV (RFC 4180) avec neutralisation des formules de tableur. */
public final class CsvWriter {

    private static final String EOL = "\r\n";

    private final Writer out;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    /** Écrit une ligne ; chaque cellule passe par {@link #cell(String)}. */
    public void row(String... cells) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        out.write(line.append(EOL).toString());
    }

    /** Valeur neutralisée (préfixe {@code '} si elle ressemble à une formule) puis entourée de guillemets si besoin. */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = formula(value) ? "'" + value : value;
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.startsWith(" ") || v.endsWith(" ");
        return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    /**
     * Les tableurs ignorent espaces et caractères de contrôle en tête de cellule : on teste le premier caractère
     * significatif (y compris les variantes pleine chasse), et tout début par tabulation ou retour chariot/ligne.
     */
    private static boolean formula(String v) {
        char first = v.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') {
            return true;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            return "=+-@\uFF1D\uFF0B\uFF0D\uFF20".indexOf(c) >= 0;
        }
        return false;
    }
}
