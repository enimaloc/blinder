package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Détecte chaque minute les liens expirés dont l'admin veut être prévenu (une seule fois par lien). Réévalue aussi,
 * à partir de l'état du lien, les alertes EXHAUSTED et THRESHOLD que le chemin des visites a dû reporter faute
 * d'avoir pu prendre le verrou du déclencheur (DENIALS, fenêtre glissante en mémoire, n'est pas rejoué), à partir
 * du deuxième passage qui les trouve en attente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryScanner {

    private final LinkNotificationSettingsRepository settings;
    private final LinkRepository links;
    private final DeliveryService deliveries;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** Liens vus en attente (par type) au dernier passage ; le scanner est planifié, donc mono-thread. */
    private final Map<TriggerType, Set<UUID>> seenPending = new EnumMap<>(TriggerType.class);

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void scan() {
        scan(Instant.now(clock));
    }

    void scan(Instant now) {
        phase("épuisement", () -> reevaluate(TriggerType.EXHAUSTED, settings.findExhaustedPending(), now));
        phase("seuil de visites", () -> reevaluate(TriggerType.THRESHOLD, settings.findThresholdPending(), now));
        phase("expiration", () -> scanExpired(now));
    }

    /** Une phase en échec (requête comprise) n'empêche pas les suivantes. */
    private void phase(String name, Runnable phase) {
        try {
            phase.run();
        } catch (RuntimeException e) {
            log.error("Réévaluation des notifications ({}) en échec", name, e);
        }
    }

    private void scanExpired(Instant now) {
        for (LinkNotificationSettings s : settings.findByOnExpiredTrue()) {
            UUID linkId = s.getLink().getId();
            if (!isExpired(s.getLink(), now)) {
                continue;
            }
            try {
                scanLink(linkId, now);
            } catch (RuntimeException e) {
                log.error("Notification d'expiration du lien {} en échec", linkId, e);
            }
        }
    }

    /**
     * EXHAUSTED et THRESHOLD ne sont réévalués que pour un lien déjà vu en attente au passage précédent : le compteur
     * d'utilisation est incrémenté dans la requête, alors que la visite n'arrive que plus tard au chemin des visites,
     * qui doit pouvoir envoyer l'alerte avec ses détails avant que le scanner ne la prenne de vitesse.
     */
    private void reevaluate(TriggerType type, List<LinkNotificationSettings> pending, Instant now) {
        Set<UUID> previous = seenPending.getOrDefault(type, Set.of());
        Set<UUID> current = new HashSet<>();
        for (LinkNotificationSettings s : pending) {
            UUID linkId = s.getLink().getId();
            current.add(linkId);
            if (!previous.contains(linkId)) {
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> links.findById(linkId).ifPresent(link -> {
                    Optional<LinkNotificationSettings> own = settings.findByLinkId(linkId);
                    if (type == TriggerType.EXHAUSTED ? isExhausted(link) : reachedThreshold(link, own)) {
                        deliveries.fireOnce(link, type, null, now, details(type, own));
                    }
                }));
            } catch (RuntimeException e) {
                log.error("Notification {} du lien {} en échec", type, linkId, e);
            }
        }
        // Ne garde que les liens encore en attente : l'ensemble reste borné par la requête.
        seenPending.put(type, current);
    }

    private static NotificationPayloads.Details details(TriggerType type, Optional<LinkNotificationSettings> own) {
        if (type != TriggerType.THRESHOLD) {
            return NotificationPayloads.Details.NONE;
        }
        return new NotificationPayloads.Details(own.map(LinkNotificationSettings::getVisitThreshold).orElse(null),
                null);
    }

    private static boolean isExhausted(Link link) {
        return link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses();
    }

    private static boolean reachedThreshold(Link link, Optional<LinkNotificationSettings> own) {
        return own.map(LinkNotificationSettings::getVisitThreshold)
                .filter(threshold -> link.getUseCount() >= threshold).isPresent();
    }

    /** Recharge le lien dans la transaction : il a pu être prolongé ou supprimé depuis la liste. */
    void scanLink(UUID linkId, Instant now) {
        transactions.executeWithoutResult(status -> links.findById(linkId)
                .filter(link -> isExpired(link, now))
                .ifPresent(link -> deliveries.fireOnce(link, TriggerType.EXPIRED, null, now,
                        NotificationPayloads.Details.NONE)));
    }

    private static boolean isExpired(Link link, Instant now) {
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now)) {
            return true;
        }
        return link.getFirstUsedAt() != null && link.getTtlAfterFirstUse() != null
                && !link.getFirstUsedAt().plus(link.getTtlAfterFirstUse()).isAfter(now);
    }
}
