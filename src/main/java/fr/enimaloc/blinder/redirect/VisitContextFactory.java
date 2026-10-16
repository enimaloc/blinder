package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.BotDetector;
import fr.enimaloc.blinder.access.VisitContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class VisitContextFactory {

    static final String PASSWORD_QUERY_PARAM = "pw";
    static final String PASSWORD_FORM_FIELD = "password";

    private final Clock clock;
    private final BotDetector botDetector;

    public VisitContext from(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new VisitContext(
                request.getRemoteAddr(),
                userAgent == null ? "" : userAgent,
                botDetector.isBot(userAgent),
                request.getMethod(),
                Instant.now(clock),
                submittedPassword(request),
                null);
    }

    private static String submittedPassword(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                ? request.getParameter(PASSWORD_FORM_FIELD)
                : request.getParameter(PASSWORD_QUERY_PARAM);
    }
}
