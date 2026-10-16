package fr.enimaloc.blinder.common;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlPolicy {

    private UrlPolicy() {
    }

    /** URL absolue http(s) avec un host. Refuse javascript:, data:, relatives, protocol-relative. */
    public static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
