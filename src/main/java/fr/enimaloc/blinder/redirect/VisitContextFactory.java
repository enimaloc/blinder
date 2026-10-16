package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.BotDetector;
import fr.enimaloc.blinder.access.VisitContext;
import fr.enimaloc.blinder.device.DeviceCookieService;
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

    static final int MAX_REFERER = 1024;

    private final Clock clock;
    private final BotDetector botDetector;
    private final DeviceCookieService deviceCookies;

    public VisitContext from(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new VisitContext(
                request.getRemoteAddr(),
                userAgent == null ? "" : userAgent,
                botDetector.isBot(userAgent),
                request.getMethod(),
                Instant.now(clock),
                submittedPassword(request),
                deviceCookies.authenticate(request).orElse(null),
                referer(request));
    }

    private static String referer(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return null;
        }
        return referer.length() > MAX_REFERER ? referer.substring(0, MAX_REFERER) : referer;
    }

    private static String submittedPassword(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                ? request.getParameter(PASSWORD_FORM_FIELD)
                : request.getParameter(PASSWORD_QUERY_PARAM);
    }
}
