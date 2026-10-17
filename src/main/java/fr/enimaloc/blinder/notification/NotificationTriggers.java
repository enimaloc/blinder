package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.stats.VisitBatchListener;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.access.DenyReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transforme les lots de visites écrits en envois de notification. Appelé dans le thread d'écriture, après le commit
 * du lot : les {@link Visit} sont détachés (seuls leurs champs simples et l'identifiant du lien sont lus), tout le
 * reste est relu dans une transaction par lien, de sorte qu'un lien en échec n'empêche pas les autres.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTriggers implements VisitBatchListener {

    private final LinkNotificationSettingsRepository settings;
    private final LinkRepository links;
    private final FiredTriggerRepository fired;
    private final DeliveryService deliveries;
    private final DenialWindow denialWindow;
    private final NotificationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public void onVisits(List<Visit> visits) {
        Map<UUID, List<Visit>> byLink = new LinkedHashMap<>();
        for (Visit visit : visits) {
            byLink.computeIfAbsent(visit.getLink().getId(), id -> new ArrayList<>()).add(visit);
        }
        byLink.forEach((linkId, linkVisits) -> {
            try {
                transactions.executeWithoutResult(status -> process(linkId, linkVisits));
            } catch (RuntimeException e) {
                log.error("Notifications du lien {} en échec", linkId, e);
            }
        });
    }

    private void process(UUID linkId, List<Visit> visits) {
        LinkNotificationSettings s = settings.findWithChannelsByLinkId(linkId).orElse(null);
        if (s == null || s.getChannels().stream().noneMatch(NotificationChannel::isEnabled)) {
            return;
        }
        Link link = links.findById(linkId).orElse(null);
        if (link == null) {
            return;
        }
        Instant now = Instant.now(clock);
        Set<TriggerType> done = EnumSet.noneOf(TriggerType.class);
        for (Visit visit : visits) {
            VisitOutcome outcome = visit.getOutcome();
            DenyReason reason = visit.getDenyReason();
            if (outcome == VisitOutcome.REDIRECTED) {
                if (s.isOnEachVisit()) {
                    deliveries.enqueue(link, TriggerType.VISIT, visit, now);
                }
                // Compteur relu en base (le lien vient d'être chargé dans cette transaction).
                Integer threshold = s.getVisitThreshold();
                if (threshold != null && link.getUseCount() >= threshold) {
                    fireOnce(link, TriggerType.THRESHOLD, visit, now, done,
                            new NotificationPayloads.Details(threshold, null));
                }
                if (s.isOnExhausted() && link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses()) {
                    fireOnce(link, TriggerType.EXHAUSTED, visit, now, done, NotificationPayloads.Details.NONE);
                }
            } else if (outcome == VisitOutcome.DENIED && reason == DenyReason.EXHAUSTED && s.isOnExhausted()) {
                fireOnce(link, TriggerType.EXHAUSTED, visit, now, done, NotificationPayloads.Details.NONE);
            } else if (outcome == VisitOutcome.DENIED && reason == DenyReason.EXPIRED && s.isOnExpired()) {
                fireOnce(link, TriggerType.EXPIRED, visit, now, done, NotificationPayloads.Details.NONE);
            }
            if (s.isOnDenials() && isRepeatableDenial(outcome, reason)
                    && denialWindow.record(linkId, visit.getOccurredAt())) {
                alertDenials(link, visit, now);
            }
        }
    }

    private static boolean isRepeatableDenial(VisitOutcome outcome, DenyReason reason) {
        return outcome == VisitOutcome.PASSWORD_REJECTED || outcome == VisitOutcome.THROTTLED
                || (outcome == VisitOutcome.DENIED
                    && (reason == DenyReason.FORBIDDEN || reason == DenyReason.HEADER_CHECK_FAILED));
    }

    private void fireOnce(Link link, TriggerType type, Visit visit, Instant now, Set<TriggerType> done,
                          NotificationPayloads.Details details) {
        if (done.add(type)) {
            deliveries.fireOnce(link, type, visit, now, details);
        }
    }

    /** Alerte puis silence pendant {@code cooldown} ; la date du dernier envoi est portée par la ligne DENIALS. */
    private void alertDenials(Link link, Visit visit, Instant now) {
        NotificationProperties.Denials denials = properties.denials();
        Instant at = visit.getOccurredAt();
        FiredTrigger last = fired.findByLinkIdAndTriggerType(link.getId(), TriggerType.DENIALS).orElse(null);
        if (last != null && last.getFiredAt().plus(denials.cooldown()).isAfter(at)) {
            return;
        }
        if (last == null) {
            last = new FiredTrigger(link, TriggerType.DENIALS);
        }
        last.setFiredAt(at);
        fired.save(last);
        deliveries.enqueue(link, TriggerType.DENIALS, visit, now,
                new NotificationPayloads.Details(denials.threshold(), denials.window().toMinutes()));
    }
}
