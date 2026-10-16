package fr.enimaloc.blinder.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fenêtre glissante en mémoire des refus par lien (perdue au redémarrage, c'est voulu). */
@Component
public class DenialWindow {

    private final int threshold;
    private final Duration window;
    private final Map<UUID, Deque<Instant>> denials = new ConcurrentHashMap<>();

    @Autowired
    public DenialWindow(NotificationProperties properties) {
        this(properties.denials().threshold(), properties.denials().window());
    }

    DenialWindow(int threshold, Duration window) {
        this.threshold = threshold;
        this.window = window;
    }

    /**
     * Enregistre un refus ; {@code true} si le seuil est atteint dans la fenêtre (la fenêtre du lien est alors vidée).
     */
    public boolean record(UUID linkId, Instant at) {
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
}
