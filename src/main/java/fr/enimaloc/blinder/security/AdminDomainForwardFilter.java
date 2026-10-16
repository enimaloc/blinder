package fr.enimaloc.blinder.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sur le domaine admin dédié, préfixe les chemins par {@code /_} : un seul jeu de routes admin.
 * S'exécute avant la chaîne Spring Security (ordre -100), qui s'applique ensuite au FORWARD.
 */
@Component
public class AdminDomainForwardFilter extends OncePerRequestFilter implements Ordered {

    private final AdminRequestMatcher matcher;

    public AdminDomainForwardFilter(AdminRequestMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = AdminRequestMatcher.pathOf(request);
        if (matcher.isAdminDomain(request) && !path.startsWith(AdminRequestMatcher.PREFIX)) {
            request.getRequestDispatcher("/_" + path).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return -110;
    }
}
