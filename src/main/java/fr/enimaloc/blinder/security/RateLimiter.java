package fr.enimaloc.blinder.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Compteur d'échecs en mémoire, fenêtre glissante, nombre de clés borné (éviction LRU).
 * Sous un flot de clés bloquées, les nouvelles clés sont refusées plutôt que de laisser les anciens blocages expirer prématurément. */
public class RateLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final int maxKeys;
    private final LinkedHashMap<String, Deque<Instant>> failures = new LinkedHashMap<>(16, 0.75f, true);

    public RateLimiter(int maxFailures, Duration window, Clock clock, int maxKeys) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be >= 1, got " + maxFailures);
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be strictly positive, got " + window);
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys must be >= 1, got " + maxKeys);
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    public synchronized boolean isBlocked(String key) {
        Deque<Instant> recent = recent(key);
        return recent != null && recent.size() >= maxFailures;
    }

    /** Enregistre un échec pour la clé. No-op si la clé est déjà bloquée. */
    public synchronized void recordFailure(String key) {
        Deque<Instant> recent = recent(key);
        if (recent == null) {
            // Nouvelle clé : essayer de la créer
            if (failures.size() >= maxKeys) {
                // Table pleine : éviction intelligente (nouvelle stratégie : purge/non-bloquée/LRU)
                evictToMakeRoom();
            }
            recent = new ArrayDeque<>();
            failures.put(key, recent);
        } else if (recent.size() >= maxFailures) {
            // Clé déjà bloquée : no-op
            return;
        }
        recent.addLast(clock.instant());
        while (recent.size() > maxFailures) {
            recent.removeFirst();
        }
    }

    public synchronized void reset(String key) {
        failures.remove(key);
    }

    public synchronized void clear() {
        failures.clear();
    }

    /** Durée avant la fin du blocage ; {@link Duration#ZERO} si la clé n'est pas bloquée. */
    public synchronized Duration retryAfter(String key) {
        Deque<Instant> recent = recent(key);
        if (recent == null || recent.size() < maxFailures) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), recent.peekFirst().plus(window));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** Purge les échecs sortis de la fenêtre ; supprime la clé si plus rien ne reste. */
    private Deque<Instant> recent(String key) {
        Deque<Instant> recent = failures.get(key);
        if (recent == null) {
            return null;
        }
        Instant cutoff = clock.instant().minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
        if (recent.isEmpty()) {
            failures.remove(key);
            return null;
        }
        return recent;
    }

    /** Purifie et évicte pour faire room pour une nouvelle clé. Stratégie : purge-before-evict, puis non-bloquée, puis LRU. */
    private void evictToMakeRoom() {
        // Étape 1 : purger les clés avec tous les échecs hors fenêtre
        Instant cutoff = clock.instant().minus(window);
        var keysSnapshot = java.util.List.copyOf(failures.keySet());
        for (String key : keysSnapshot) {
            Deque<Instant> deque = failures.get(key);
            if (deque != null) {
                // Nettoyer les entrées hors fenêtre
                while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                    deque.removeFirst();
                }
                if (deque.isEmpty()) {
                    failures.remove(key);
                }
            }
        }

        // Si la table n'est pas encore pleine, on est bon
        if (failures.size() < maxKeys) {
            return;
        }

        // Étape 2 : essayer d'éviter les clés bloquées - éviter la première clé non-bloquée en ordre d'accès
        var keysSnapshot2 = java.util.List.copyOf(failures.keySet());
        for (String key : keysSnapshot2) {
            Deque<Instant> deque = failures.get(key);
            if (deque != null && deque.size() < maxFailures) {
                // Clé non-bloquée trouvée : la supprimer (elle est la plus anciennement utilisée d'après l'ordre d'itération LinkedHashMap)
                failures.remove(key);
                return;
            }
        }

        // Étape 3 : tous les clés sont bloqués, éviter le LRU (first in order)
        if (!failures.isEmpty()) {
            Iterator<String> iter = failures.keySet().iterator();
            if (iter.hasNext()) {
                failures.remove(iter.next());
            }
        }
    }
}
