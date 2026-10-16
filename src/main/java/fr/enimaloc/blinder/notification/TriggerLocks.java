package fr.enimaloc.blinder.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sérialise en mémoire les déclencheurs « une seule fois » par (lien, type), jusqu'à la fin de la transaction
 * appelante : le second appelant ne vérifie {@link FiredTrigger} qu'une fois le premier validé ou annulé, et ne
 * tente donc jamais une insertion en double (qui annulerait toute sa transaction, avec ses autres alertes).
 * Un détenteur du verrou tient toujours une connexion : compatible avec un pool d'une seule connexion (SQLite).
 */
@Component
public class TriggerLocks {

    static final Duration WAIT = Duration.ofSeconds(30);

    private record Key(UUID linkId, TriggerType type) {
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Prend le verrou de (lien, type) jusqu'à la fin de la transaction courante ; {@code false} si l'attente dépasse
     * {@link #WAIT} (l'appelant renonce alors pour cette fois). Hors transaction, le verrou est rendu tout de suite :
     * il n'a de sens qu'avec une transaction.
     */
    public boolean acquireForTransaction(UUID linkId, TriggerType type) {
        Key key = new Key(linkId, type);
        Entry entry = entries.compute(key, (k, e) -> {
            Entry current = e == null ? new Entry() : e;
            current.users++;
            return current;
        });
        boolean locked;
        try {
            locked = entry.lock.tryLock(WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            release(key, entry, false);
            return false;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    release(key, entry, true);
                }
            });
        } else {
            release(key, entry, true);
        }
        return true;
    }

    private void release(Key key, Entry entry, boolean unlock) {
        if (unlock) {
            entry.lock.unlock();
        }
        entries.computeIfPresent(key, (k, e) -> --e.users == 0 ? null : e);
    }

    /** Nombre de clés suivies (tests). */
    int size() {
        return entries.size();
    }
}
