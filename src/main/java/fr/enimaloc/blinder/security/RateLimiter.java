package fr.enimaloc.blinder.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;

/** Compteur d'échecs en mémoire, fenêtre glissante, nombre de clés borné (éviction LRU). Instance unique. */
public class RateLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final int maxKeys;
    private final LinkedHashMap<String, Deque<Instant>> failures = new LinkedHashMap<>(16, 0.75f, true);

    public RateLimiter(int maxFailures, Duration window, Clock clock, int maxKeys) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    public synchronized boolean isBlocked(String key) {
        Deque<Instant> recent = recent(key);
        return recent != null && recent.size() >= maxFailures;
    }

    public synchronized void recordFailure(String key) {
        Deque<Instant> recent = recent(key);
        if (recent == null) {
            if (failures.size() >= maxKeys) {
                evictLeastRecentlyUsed();
            }
            recent = new ArrayDeque<>();
            failures.put(key, recent);
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

    private void evictLeastRecentlyUsed() {
        Iterator<String> eldest = failures.keySet().iterator();
        if (eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }
}
