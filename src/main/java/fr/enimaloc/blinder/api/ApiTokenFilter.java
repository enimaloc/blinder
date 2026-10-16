package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.api.token.ApiTokenService;
import fr.enimaloc.blinder.security.RateLimitKeys;
import fr.enimaloc.blinder.security.RateLimiter;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Authentifie {@code Authorization: Bearer <jeton>} sur la chaîne API. Sans en-tête Bearer, la requête continue anonyme
 * (et finit en 401). Chaque jeton refusé compte comme un échec pour l'IP ; une IP bloquée reçoit 429, même avec un bon jeton.
 * Instancié par SecurityConfig (pas un bean).
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiTokenService tokens;
    private final RateLimiter failures;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ApiTokenFilter(ApiTokenService tokens, RateLimiter failures, AuthenticationEntryPoint entryPoint) {
        this.tokens = tokens;
        this.failures = failures;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        String key = RateLimitKeys.apiAuth(request.getRemoteAddr());
        if (failures.isBlocked(key)) {
            tooManyFailures(response, failures.retryAfter(key));
            return;
        }
        Optional<User> owner = tokens.authenticate(header.substring(BEARER.length()).trim());
        if (owner.isEmpty()) {
            failures.recordFailure(key);
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API token"));
            return;
        }
        BlinderUserDetails principal = new BlinderUserDetails(owner.get());
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }

    private static void tooManyFailures(HttpServletResponse response, Duration retryAfter) throws IOException {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        ApiProblems.write(response, HttpStatus.TOO_MANY_REQUESTS,
                "Trop de jetons refusés depuis cette adresse, réessayez plus tard");
    }
}
