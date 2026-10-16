package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.StaleEditException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    static ProblemDetail invalid(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** 401 de la chaîne API, avec le défi {@code WWW-Authenticate: Bearer}. */
    public static AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
        };
    }

    public static AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(response, HttpStatus.FORBIDDEN, "Accès refusé");
    }

    /** Écrit un problème hors de Spring MVC (filtres de sécurité) ; {@code detail} est un texte fixe. */
    static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + json(status.getReasonPhrase())
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + json(detail) + "\"}");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
