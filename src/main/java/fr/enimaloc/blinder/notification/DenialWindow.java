package fr.enimaloc.blinder.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fenêtre glissante en mémoire des refus par lien (perdue au redémarrage, c'est voulu). Les liens dont tous les refus
 * sont sortis de la fenêtre sont oubliés (purge au plus une fois par fenêtre, lors d'un enregistrement). Pas de
 * plafond avec éviction : évincer un lien remettrait son compte à zéro et permettrait d'étouffer son alerte. La taille
 * reste bornée par le nombre de liens ayant l'alerte « refus répétés » activée et un refus récent.
 */
@Component
public class DenialWindow {

    private final int threshold;
    private final Duration window;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> denials = new ConcurrentHashMap<>();
    private volatile Instant nextPurge = Instant.MIN;

    @Autowired
    public DenialWindow(NotificationProperties properties, Clock clock) {
        this(properties.denials().threshold(), properties.denials().window(), clock);
    }

    DenialWindow(int threshold, Duration window) {
        this(threshold, window, Clock.systemUTC());
    }

    DenialWindow(int threshold, Duration window, Clock clock) {
        this.threshold = threshold;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Enregistre un refus ; {@code true} si le seuil est atteint dans la fenêtre (la fenêtre du lien est alors vidée).
     */
    public boolean record(UUID linkId, Instant at) {
        Instant now = clock.instant();
        if (!now.isBefore(nextPurge)) {
            purge(now);
        }
        boolean[] fired = new boolean[1];
        // compute() est atomique par clé : deux threads ne peuvent pas déclencher deux fois pour le même lot de refus.
        denials.compute(linkId, (id, instants) -> {
            Deque<Instant> deque = instants == null ? new ArrayDeque<>() : instants;
            Instant oldest = at.minus(window);
            deque.removeIf(instant -> instant.isBefore(oldest));
            deque.addLast(at);
            if (deque.size() >= threshold) {
                fired[0] = true;
                return null;
            }
            return deque;
        });
        return fired[0];
    }

    /** Retire les liens dont le refus le plus récent est sorti de la fenêtre. */
    private void purge(Instant now) {
        nextPurge = now.plus(window);
        Instant oldest = now.minus(window);
        for (UUID linkId : denials.keySet()) {
            denials.computeIfPresent(linkId, (id, deque) -> isStale(deque, oldest) ? null : deque);
        }
    }

    private static boolean isStale(Deque<Instant> deque, Instant oldest) {
        Instant last = deque.peekLast();
        return last == null || last.isBefore(oldest);
    }

    /** Nombre de liens suivis (tests). */
    int trackedLinks() {
        return denials.size();
    }
}
