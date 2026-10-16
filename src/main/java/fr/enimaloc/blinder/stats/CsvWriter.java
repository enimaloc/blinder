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
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            v = "'" + v;
        }
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.startsWith(" ") || v.endsWith(" ");
        return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
