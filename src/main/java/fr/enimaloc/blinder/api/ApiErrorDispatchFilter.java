package fr.enimaloc.blinder.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Erreurs du conteneur re-dispatchées vers {@code /error} : sous {@code /_/api/} la réponse est un problème JSON au lieu
 * de la page d'erreur HTML. Enregistré pour le seul dispatch ERROR (voir {@link ApiErrorConfig}), pas un bean de filtre.
 */
public class ApiErrorDispatchFilter extends OncePerRequestFilter {

    private final ProblemWriter problems;

    public ApiErrorDispatchFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isApiError(request)) {
            chain.doFilter(request, response);
            return;
        }
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value && HttpStatus.resolve(value) != null
                ? HttpStatus.valueOf(value) : HttpStatus.INTERNAL_SERVER_ERROR;
        problems.write(response, status, ApiProblems.detailFor(status));
    }

    private static boolean isApiError(HttpServletRequest request) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri)) {
            return false;
        }
        UrlPathHelper helper = UrlPathHelper.defaultInstance;
        String path = helper.removeSemicolonContent(helper.decodeRequestString(request, uri));
        String context = request.getContextPath();
        if (!context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith(ApiRequestMatcher.PREFIX);
    }
}
