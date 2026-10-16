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

/** Détecte chaque minute les liens expirés dont l'admin veut être prévenu (une seule fois par lien). */
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
