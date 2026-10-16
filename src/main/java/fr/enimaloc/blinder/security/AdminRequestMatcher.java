package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.HostNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

@Component
public class AdminRequestMatcher implements RequestMatcher {

    public static final String PREFIX = "/_/";

    private final String adminDomain;

    public AdminRequestMatcher(BlinderProperties properties) {
        String configured = properties.admin().domain();
        this.adminDomain = configured == null || configured.isBlank() ? null : HostNormalizer.normalize(configured);
    }

    public boolean isAdminDomain(HttpServletRequest request) {
        return isAdminHost(request.getServerName());
    }

    /** @return {@code true} si {@code host} (normalisé ici) est le domaine dédié au panel d'administration. */
    public boolean isAdminHost(String host) {
        return adminDomain != null && adminDomain.equals(HostNormalizer.normalize(host));
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return isAdminDomain(request) || pathOf(request).startsWith(PREFIX);
    }

    /**
     * Chemin décodé et normalisé (percent-decoding, paramètres ;x retirés), comme le fait Spring MVC :
     * sinon {@code /%5F/} contournerait la chaîne admin tout en atteignant les handlers {@code /_/}.
     */
    public static String pathOf(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
