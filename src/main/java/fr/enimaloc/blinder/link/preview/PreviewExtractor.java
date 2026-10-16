package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.common.UrlPolicy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Extrait titre / description / image d'une page HTML (Open Graph, Twitter, puis repli HTML). */
public final class PreviewExtractor {

    private PreviewExtractor() {
    }

    /** @return les métadonnées trouvées, ou {@code null} si aucune */
    public static PreviewData extract(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri);
        String title = first(content(doc, "property", "og:title"), content(doc, "name", "twitter:title"),
                blankToNull(doc.title()));
        String description = first(content(doc, "property", "og:description"),
                content(doc, "name", "twitter:description"), content(doc, "name", "description"));
        String image = first(absolute(doc, "property", "og:image"), absolute(doc, "name", "twitter:image"));
        if (image != null && (image.length() > 2048 || !UrlPolicy.isHttpUrl(image))) {
            image = null;
        }
        title = truncate(title, 200);
        description = truncate(description, 500);
        return title == null && description == null && image == null ? null : new PreviewData(title, description, image);
    }

    private static String content(Document doc, String attribute, String value) {
        Element element = doc.selectFirst("meta[" + attribute + "=\"" + value + "\"]");
        return element == null ? null : blankToNull(element.attr("content"));
    }

    private static String absolute(Document doc, String attribute, String value) {
        Element element = doc.selectFirst("meta[" + attribute + "=\"" + value + "\"]");
        return element == null ? null : blankToNull(element.absUrl("content"));
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
