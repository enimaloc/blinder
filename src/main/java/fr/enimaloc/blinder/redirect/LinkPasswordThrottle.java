package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.security.RateLimitKeys;
import fr.enimaloc.blinder.security.RateLimiters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Politique de limitation des essais de mot de passe d'un lien : réservation atomique par client puis globale. */
@Component
@RequiredArgsConstructor
public class LinkPasswordThrottle {

    private final RateLimiters rateLimiters;

    /** Réserve un essai (par client puis global) ; vide = réservé, présent = refusé avec le délai à annoncer. */
    public Optional<Duration> tryReserve(UUID linkId, String clientIp) {
        String clientKey = RateLimitKeys.linkPassword(linkId, clientIp);
        String aggregateKey = RateLimitKeys.linkPasswordAggregate(linkId);
        if (!rateLimiters.linkPassword().acquire(clientKey)) {
            return Optional.of(retryAfter(clientKey, aggregateKey));
        }
        if (!rateLimiters.linkPasswordAggregate().acquire(aggregateKey)) {
            rateLimiters.linkPassword().release(clientKey);
            return Optional.of(retryAfter(clientKey, aggregateKey));
        }
        return Optional.empty();
    }

    /** Mot de passe correct : remet le client à zéro et rend l'essai global. */
    public void succeeded(UUID linkId, String clientIp) {
        rateLimiters.linkPassword().reset(RateLimitKeys.linkPassword(linkId, clientIp));
        rateLimiters.linkPasswordAggregate().release(RateLimitKeys.linkPasswordAggregate(linkId));
    }

    /** Mot de passe refusé : les deux réservations restent comptées comme échecs. */
    public void rejected(UUID linkId, String clientIp) {
        // Intentionnellement vide : l'essai est déjà compté par tryReserve.
    }

    /** La décision n'était pas un verdict sur le mot de passe : rend les deux réservations. */
    public void abandoned(UUID linkId, String clientIp) {
        rateLimiters.linkPassword().release(RateLimitKeys.linkPassword(linkId, clientIp));
        rateLimiters.linkPasswordAggregate().release(RateLimitKeys.linkPasswordAggregate(linkId));
    }

    private Duration retryAfter(String clientKey, String aggregateKey) {
        Duration client = rateLimiters.linkPassword().retryAfter(clientKey);
        Duration aggregate = rateLimiters.linkPasswordAggregate().retryAfter(aggregateKey);
        return client.compareTo(aggregate) >= 0 ? client : aggregate;
    }
}
