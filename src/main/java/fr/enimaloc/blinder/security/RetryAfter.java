package fr.enimaloc.blinder.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

/** Calcul et pose de l'en-tête {@code Retry-After} (secondes entières, arrondies au supérieur, au moins 1). */
public final class RetryAfter {

    private RetryAfter() {
    }

    public static long seconds(Duration remaining) {
        return Math.max(1, (remaining.toMillis() + 999) / 1000);
    }

    public static void set(HttpServletResponse response, Duration remaining) {
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds(remaining)));
    }
}
