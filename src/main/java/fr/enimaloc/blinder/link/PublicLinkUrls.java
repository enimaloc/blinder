package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.PublicUrls;
import jakarta.servlet.http.HttpServletRequest;

public final class PublicLinkUrls {

    private PublicLinkUrls() {
    }

    /** {@code link.domain} doit être chargé. */
    public static String of(HttpServletRequest request, Link link) {
        return PublicUrls.base(request.getScheme(), link.getDomain().getHost(), request.getServerPort())
                + "/" + link.getSlug();
    }
}
