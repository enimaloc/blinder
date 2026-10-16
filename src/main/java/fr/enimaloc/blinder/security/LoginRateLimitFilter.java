package fr.enimaloc.blinder.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Réserve atomiquement une tentative pour tout POST /_/login ; refuse (redirection ?locked) quand l'IP a épuisé son quota.
 * Instancié par SecurityConfig (pas un bean). */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter attempts;

    public LoginRateLimitFilter(RateLimiter attempts) {
        this.attempts = attempts;
    }

    static String key(HttpServletRequest request) {
        return RateLimitKeys.adminLogin(request.getRemoteAddr());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && "/_/login".equals(AdminRequestMatcher.pathOf(request))
                && !attempts.acquire(key(request))) {
            response.sendRedirect(request.getContextPath() + "/_/login?locked");
            return;
        }
        chain.doFilter(request, response);
    }
}
