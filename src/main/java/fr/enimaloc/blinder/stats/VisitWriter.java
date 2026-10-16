package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File bornée + un thread unique qui écrit les visites par lots (avec les compteurs journaliers).
 * Le chemin de redirection n'appelle que {@link #offer}, qui ne bloque ni ne lève jamais.
 */
@Slf4j
@Component
public class VisitWriter implements SmartLifecycle {

    static final int BATCH_SIZE = 499;
    private static final long MAX_POLL_MILLIS = 100;

    private final LinkRepository links;
    private final VisitRepository visits;
    private final VisitDailyStatRepository dailyStats;
    private final TransactionTemplate transactions;
    private final StatsProperties properties;
    private final BlinderProperties blinder;
    private final GeoLocator geo;
    private final ObjectProvider<VisitBatchListener> listeners;

    private final BlockingQueue<VisitRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    /** « drainer + écrire » est exclusif entre le thread et {@link #flush()} ; équitable pour que flush passe vite. */
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private volatile boolean running;
    private Thread thread;

    public VisitWriter(LinkRepository links, VisitRepository visits, VisitDailyStatRepository dailyStats,
                       TransactionTemplate transactions, StatsProperties properties, BlinderProperties blinder,
                       GeoLocator geo, ObjectProvider<VisitBatchListener> listeners) {
        this.links = links;
        this.visits = visits;
        this.dailyStats = dailyStats;
        this.transactions = transactions;
        this.properties = properties;
        this.blinder = blinder;
        this.geo = geo;
        this.listeners = listeners;
        this.queue = new LinkedBlockingQueue<>(properties.queueCapacity());
    }

    /** Jamais bloquant : file pleine → la visite est comptée comme perdue. */
    public boolean offer(VisitRecord record) {
        if (queue.offer(record)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    public long dropped() {
        return dropped.get();
    }

    /** Écrit tout de suite, dans le thread appelant, ce qui est en file. */
    public void flush() {
        writeLock.lock();
        try {
            List<VisitRecord> batch = new ArrayList<>();
            while (queue.drainTo(batch, BATCH_SIZE) > 0) {
                writeBatch(batch);
                batch = new ArrayList<>();
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void loop() {
        long pollMillis = Math.max(1, Math.min(MAX_POLL_MILLIS, properties.flushInterval().toMillis()));
        while (running) {
            try {
                writeLock.lock();
                try {
                    // Le poll est sous le verrou : flush() ne peut pas rater un enregistrement « en vol ».
                    VisitRecord first = queue.poll(pollMillis, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        List<VisitRecord> batch = new ArrayList<>();
                        batch.add(first);
                        queue.drainTo(batch, BATCH_SIZE - 1);
                        writeBatch(batch);
                    }
                } finally {
                    writeLock.unlock();
                }
            } catch (InterruptedException e) {
                running = false;
                Thread.currentThread().interrupt();
                log.error("Thread d'écriture des visites interrompu, arrêt de la boucle");
                return;
            } catch (Throwable t) {
                log.error("Erreur inattendue dans le thread d'écriture des visites", t);
            }
        }
    }

    void writeBatch(List<VisitRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Enriched> enriched = new ArrayList<>(batch.size());
        for (VisitRecord record : batch) {
            try {
                enriched.add(enrich(record));
            } catch (Throwable t) {
                log.error("Enrichissement d'une visite du lien {} impossible, visite abandonnée", record.linkId(), t);
                dropped.incrementAndGet();
            }
        }
        if (enriched.isEmpty()) {
            return;
        }
        List<Visit> persisted = persistBatch(enriched);
        if (persisted == null || persisted.isEmpty()) {
            return;
        }
        try {
            listeners.orderedStream().forEach(listener -> {
                try {
                    listener.onVisits(persisted);
                } catch (Throwable t) {
                    log.error("Écouteur de visites en échec", t);
                }
            });
        } catch (Throwable t) {
            log.error("Écouteurs de visites inaccessibles", t);
        }
    }

    private List<Visit> persistBatch(List<Enriched> enriched) {
        try {
            return transactions.execute(status -> persist(enriched));
        } catch (Throwable t) {
            // Typiquement un lien supprimé entre sa lecture et la validation : on isole la ou les visites fautives.
            // Une seule trace complète par lot, même si la base est indisponible pour toutes ses visites.
            log.error("Écriture d'un lot de {} visites impossible, nouvel essai visite par visite", enriched.size(), t);
            return writeOneByOne(enriched);
        }
    }

    /**
     * Une transaction par visite : le lien est relu, donc une visite d'un lien supprimé entre-temps est ignorée
     * (comme à l'ordinaire) ; seules les visites dont l'écriture échoue encore sont comptées comme perdues. Chaque
     * échec individuel n'est journalisé qu'en DEBUG, suivi d'une ligne de synthèse pour le lot.
     */
    private List<Visit> writeOneByOne(List<Enriched> enriched) {
        List<Visit> written = new ArrayList<>(enriched.size());
        int lost = 0;
        for (Enriched e : enriched) {
            try {
                List<Visit> one = transactions.execute(status -> persist(List.of(e)));
                if (one != null) {
                    written.addAll(one);
                }
            } catch (Throwable t) {
                log.debug("Écriture d'une visite du lien {} impossible, visite abandonnée", e.record().linkId(), t);
                lost++;
            }
        }
        dropped.addAndGet(lost);
        if (lost > 0) {
            log.error("Nouvel essai visite par visite : {} visite(s) perdue(s) sur {}", lost, enriched.size());
        } else {
            log.warn("Nouvel essai visite par visite : aucune visite perdue sur {}", enriched.size());
        }
        return written;
    }

    private record Enriched(VisitRecord record, UserAgentClassifier.Result agent, String refererHost,
                            Optional<GeoLocation> location) {
    }

    private Enriched enrich(VisitRecord record) {
        Optional<GeoLocation> location = record.ip() == null ? Optional.empty() : geo.locate(record.ip());
        return new Enriched(record, UserAgentClassifier.classify(record.userAgent()),
                RefererHosts.hostOf(record.referer()), location == null ? Optional.empty() : location);
    }

    private record StatKey(UUID linkId, LocalDate day, VisitOutcome outcome) {
    }

    private List<Visit> persist(List<Enriched> batch) {
        Map<UUID, Optional<Link>> linkCache = new HashMap<>();
        Map<StatKey, VisitDailyStat> statCache = new HashMap<>();
        List<Visit> written = new ArrayList<>(batch.size());
        for (Enriched e : batch) {
            VisitRecord r = e.record();
            Link link = linkCache.computeIfAbsent(r.linkId(), links::findById).orElse(null);
            if (link == null) {
                continue;
            }
            Visit visit = new Visit(link, link.getOwner(), r.occurredAt(), r.outcome(), r.denyReason(),
                    r.httpMethod(), r.bot(), r.ip(), r.userAgent(), e.agent().browser(), e.agent().os(),
                    r.referer(), e.refererHost(), e.location().map(GeoLocation::country).orElse(null),
                    e.location().map(GeoLocation::city).orElse(null));
            visits.save(visit);
            written.add(visit);

            LocalDate day = r.occurredAt().atZone(blinder.admin().timeZone()).toLocalDate();
            VisitDailyStat stat = statCache.computeIfAbsent(new StatKey(r.linkId(), day, r.outcome()), key ->
                    dailyStats.findByLinkIdAndDayAndOutcome(key.linkId(), key.day(), key.outcome())
                            .orElseGet(() -> dailyStats.save(new VisitDailyStat(link, link.getOwner(), key.day(), key.outcome()))));
            stat.increment(1);
        }
        return written;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "visit-writer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                log.error("Le thread d'écriture des visites ne s'est pas arrêté en 5 s");
            } else {
                thread = null;
            }
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.writerAutostart();
    }

    /**
     * Phase 0 : démarre avant et s'arrête après le serveur web (arrêt gracieux en phase proche de MAX_VALUE),
     * pour ne pas perdre les visites des requêtes en cours de vidange. DataSource et EntityManagerFactory ne sont
     * pas des beans Lifecycle : ils sont détruits après l'arrêt de tous les Lifecycle.
     */
    @Override
    public int getPhase() {
        return 0;
    }
}
