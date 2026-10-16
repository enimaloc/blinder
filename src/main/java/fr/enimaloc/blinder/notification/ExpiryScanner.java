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
import java.util.UUID;

/**
 * Détecte chaque minute les liens expirés dont l'admin veut être prévenu (une seule fois par lien). Réévalue aussi,
 * à partir de l'état du lien, les alertes EXHAUSTED et THRESHOLD que le chemin des visites a dû reporter faute
 * d'avoir pu prendre le verrou du déclencheur (DENIALS, fenêtre glissante en mémoire, n'est pas rejoué).
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

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void scan() {
        scan(Instant.now(clock));
    }

    void scan(Instant now) {
        reevaluate(TriggerType.EXHAUSTED, settings.findExhaustedPending(), now);
        reevaluate(TriggerType.THRESHOLD, settings.findThresholdPending(), now);
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

    private void reevaluate(TriggerType type, java.util.List<LinkNotificationSettings> pending, Instant now) {
        for (LinkNotificationSettings s : pending) {
            UUID linkId = s.getLink().getId();
            try {
                transactions.executeWithoutResult(status -> links.findById(linkId).ifPresent(link -> {
                    if (type == TriggerType.EXHAUSTED ? isExhausted(link) : reachedThreshold(link)) {
                        deliveries.fireOnce(link, type, null, now, details(type, link));
                    }
                }));
            } catch (RuntimeException e) {
                log.error("Notification {} du lien {} en échec", type, linkId, e);
            }
        }
    }

    private NotificationPayloads.Details details(TriggerType type, Link link) {
        if (type != TriggerType.THRESHOLD) {
            return NotificationPayloads.Details.NONE;
        }
        return new NotificationPayloads.Details(settings.findByLinkId(link.getId())
                .map(LinkNotificationSettings::getVisitThreshold).orElse(null), null);
    }

    private static boolean isExhausted(Link link) {
        return link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses();
    }

    private boolean reachedThreshold(Link link) {
        return settings.findByLinkId(link.getId()).map(LinkNotificationSettings::getVisitThreshold)
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
