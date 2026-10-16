package fr.enimaloc.blinder.common;

public final class PublicUrls {

    private PublicUrls() {
    }

    /** {@code scheme://host[:port]} — le port est omis s'il est standard pour le schéma. */
    public static String base(String scheme, String host, int port) {
        boolean defaultPort = port <= 0
                || ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
