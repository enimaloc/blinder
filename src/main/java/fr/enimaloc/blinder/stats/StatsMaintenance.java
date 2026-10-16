package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.notification.DeliveryStatus;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Purge quotidienne : visites au-delà de la rétention, livraisons terminées de plus de 30 jours. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsMaintenance {

    static final int VISIT_BATCH = 1000;
    static final Duration DELIVERY_RETENTION = Duration.ofDays(30);

    private final VisitRepository visits;
    private final NotificationDeliveryRepository deliveries;
    private final StatsProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void purge() {
        Instant now = Instant.now(clock);
        Duration retention = properties.retention();
        if (retention != null && !retention.isZero() && !retention.isNegative()) {
            try {
                int removed = purgeVisits(now.minus(retention), VISIT_BATCH);
                log.info("Purge : {} visites supprimées", removed);
            } catch (RuntimeException e) {
                log.error("Purge des visites en échec", e);
            }
        }
        try {
            int old = deliveries.deleteByStatusNotAndUpdatedAtBefore(DeliveryStatus.PENDING, now.minus(DELIVERY_RETENTION));
            log.info("Purge : {} livraisons supprimées", old);
        } catch (RuntimeException e) {
            log.error("Purge des livraisons en échec", e);
        }
    }

    /** Supprime par lots, chacun dans sa propre courte transaction (le verrou d'écriture n'est jamais gardé longtemps). */
    int purgeVisits(Instant cutoff, int batch) {
        int total = 0;
        while (true) {
            List<UUID> ids = visits.findIdsOlderThan(cutoff, PageRequest.ofSize(batch));
            if (ids.isEmpty()) {
                return total;
            }
            total += visits.deleteByIdIn(ids);
            if (ids.size() < batch) {
                return total;
            }
        }
    }
}
