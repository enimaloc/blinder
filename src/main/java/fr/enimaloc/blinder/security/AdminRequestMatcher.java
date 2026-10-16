package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.HostNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

@Component
public class AdminRequestMatcher implements RequestMatcher {

    public static final String PREFIX = "/_/";

    private final String adminDomain;

    public AdminRequestMatcher(BlinderProperties properties) {
        String configured = properties.admin().domain();
        this.adminDomain = configured == null || configured.isBlank() ? null : HostNormalizer.normalize(configured);
    }

    public boolean isAdminDomain(HttpServletRequest request) {
        return adminDomain != null && adminDomain.equals(HostNormalizer.normalize(request.getServerName()));
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return isAdminDomain(request) || pathOf(request).startsWith(PREFIX);
    }

    static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
