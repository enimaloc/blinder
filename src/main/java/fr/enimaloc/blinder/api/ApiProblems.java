package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.StaleEditException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Erreurs de l'API au format Problem Details (RFC 9457), messages en français comme le panel. */
@RestControllerAdvice(basePackages = "fr.enimaloc.blinder.api")
public class ApiProblems extends ResponseEntityExceptionHandler {

    static final String UNAUTHORIZED = "Jeton d'API manquant ou invalide";
    static final String INVALID = "Données invalides";
    static final String STALE = "Modifié entre-temps";
    static final String CONFLICT = "Conflit avec une donnée existante";
    static final String UNREADABLE = "Corps de requête illisible";
    static final String UNREADABLE_REQUEST = "Requête invalide";
    static final String INTERNAL = "Erreur interne";
    static final String INVALID_PARAMETER = "Paramètre invalide : ";
    static final String MISSING_PARAMETER = "Paramètre manquant : ";

    /** Texte français fixe d'un statut d'erreur sans détail applicatif (jamais le message d'une exception). */
    static String detailFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> UNREADABLE_REQUEST;
            case 404 -> "Introuvable";
            case 405 -> "Méthode non autorisée";
            case 406 -> "Format de réponse non disponible";
            case 415 -> "Type de contenu non pris en charge";
            default -> status.is5xxServerError() ? INTERNAL : "Requête refusée";
        };
    }

    @ExceptionHandler(FieldErrorException.class)
    ProblemDetail fieldError(FieldErrorException e) {
        return invalid(Map.of(e.field(), e.getMessage()));
    }

    @ExceptionHandler(InvalidFieldsException.class)
    ProblemDetail invalidFields(InvalidFieldsException e) {
        return invalid(new TreeMap<>(e.errors()));
    }

    /** Erreur de liaison JSON hors convertisseur MVC (fusion d'un PATCH). */
    @ExceptionHandler(JacksonException.class)
    ProblemDetail unreadable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNREADABLE);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException e) {
        String detail = e.getReason() != null ? e.getReason()
                : e.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? "Introuvable" : null;
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), detail);
    }

    @ExceptionHandler({StaleEditException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail stale() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, STALE);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CONFLICT);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(invalid(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNREADABLE));
    }

    /** Paramètre de requête ou de chemin non convertible : nom du paramètre seulement, jamais la valeur reçue. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ex.getPropertyName();
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                name != null ? INVALID_PARAMETER + name : UNREADABLE_REQUEST));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                MISSING_PARAMETER + ex.getParameterName()));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detailFor(status)));
    }

    @Override
    protected ResponseEntity<Object> handleConversionNotSupported(ConversionNotSupportedException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.internalServerError()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL));
    }

    static ProblemDetail invalid(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** 401 de la chaîne API, avec le défi {@code WWW-Authenticate: Bearer}. */
    public static AuthenticationEntryPoint unauthorized(ProblemWriter problems) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problems.write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
        };
    }

    public static AccessDeniedHandler forbidden(ProblemWriter problems) {
        return (request, response, exception) -> problems.write(response, HttpStatus.FORBIDDEN, "Accès refusé");
    }
}
