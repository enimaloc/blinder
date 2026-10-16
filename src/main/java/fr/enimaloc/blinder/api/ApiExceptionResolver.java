package fr.enimaloc.blinder.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * Filet de sécurité sous {@code /_/api/} pour ce que {@link ApiProblems} ne voit pas : route inconnue (404), méthode non
 * permise (405) et exception inattendue (500, sans détail interne). Passe après les handlers du contrôleur et avant les
 * résolveurs par défaut, qui renverraient une erreur de conteneur.
 */
public class ApiExceptionResolver implements HandlerExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionResolver.class);

    private final ProblemWriter problems;

    public ApiExceptionResolver(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
                                         Exception ex) {
        if (!new ApiRequestMatcher().matches(request)) {
            return null;
        }
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (ex instanceof ErrorResponse error) {
            status = error.getStatusCode();
            error.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        } else {
            log.error("Erreur inattendue sur {} {}", request.getMethod(), request.getRequestURI(), ex);
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        try {
            problems.write(response, resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiProblems.detailFor(status));
        } catch (IOException e) {
            log.warn("Impossible d'écrire le problème d'API", e);
        }
        return new ModelAndView();
    }
}
