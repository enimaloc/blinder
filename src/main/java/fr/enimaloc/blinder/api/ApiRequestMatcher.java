package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.security.AdminRequestMatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Requêtes de l'API REST : chemin décodé (comme la chaîne admin) commençant par {@code /_/api/}, quel que soit l'hôte. */
public class ApiRequestMatcher implements RequestMatcher {

    public static final String PREFIX = "/_/api/";

    @Override
    public boolean matches(HttpServletRequest request) {
        return AdminRequestMatcher.pathOf(request).startsWith(PREFIX);
    }
}
