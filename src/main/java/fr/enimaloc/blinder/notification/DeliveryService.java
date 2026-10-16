package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Création des envois en file ; l'expédition elle-même est faite par le répartiteur. */
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final LinkNotificationSettingsRepository settings;
    private final NotificationChannelRepository channels;
    private final NotificationDeliveryRepository deliveries;
    private final FiredTriggerRepository fired;
    private final NotificationPayloads payloads;
    private final Clock clock;

    @Transactional
    public void enqueue(Link link, TriggerType event, Visit visitOrNull, Instant now) {
        enqueue(link, event, visitOrNull, now, NotificationPayloads.Details.NONE);
    }

    /** Un envoi {@code PENDING} par canal activé des réglages du lien, dû immédiatement. */
    @Transactional
    public void enqueue(Link link, TriggerType event, Visit visitOrNull, Instant now,
                        NotificationPayloads.Details details) {
        List<NotificationChannel> active = activeChannels(link);
        if (active.isEmpty()) {
            return;
        }
        String payload = payloads.json(event, now, link, visitOrNull, details);
        for (NotificationChannel channel : active) {
            deliveries.save(new NotificationDelivery(link.getOwner(), channel, link.getId(),
                    NotificationPayloads.label(link), event, payload, now));
        }
    }

    private List<NotificationChannel> activeChannels(Link link) {
        return settings.findWithChannelsByLinkId(link.getId())
                .map(s -> s.getChannels().stream().filter(NotificationChannel::isEnabled).toList())
                .orElse(List.of());
    }

    /** Test d'un canal du propriétaire : pas de lien, libellé « test ». */
    @Transactional
    public void enqueueTest(User owner, UUID channelId) {
        NotificationChannel channel = channels.findByIdAndOwner(channelId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channel.isEnabled()) {
            throw new FieldErrorException("channel", "Canal désactivé");
        }
        Instant now = Instant.now(clock);
        deliveries.save(new NotificationDelivery(owner, channel, null, "test", TriggerType.TEST,
                payloads.json(TriggerType.TEST, now, null, null), now));
    }

    /** Remet un envoi échoué en file, dû immédiatement. 404 si autre propriétaire ; {@code false} si pas {@code FAILED}. */
    @Transactional
    public boolean retry(User owner, UUID deliveryId) {
        NotificationDelivery delivery = deliveries.findByIdAndOwner(deliveryId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (delivery.getStatus() != DeliveryStatus.FAILED) {
            return false;
        }
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(Instant.now(clock));
        return true;
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> recent(User owner) {
        List<NotificationDelivery> found = deliveries.findTop100ByOwnerOrderByCreatedAtDesc(owner);
        found.forEach(d -> d.getChannel().getName());
        return found;
    }

    /** « Une seule fois » : enregistre le déclencheur puis envoie ; {@code false} s'il avait déjà été déclenché. */
    @Transactional
    public boolean fireOnce(Link link, TriggerType event, Visit visitOrNull, Instant now,
                            NotificationPayloads.Details details) {
        // Sans canal actif on ne « consomme » pas le déclencheur : il partira quand un canal sera activé.
        if (activeChannels(link).isEmpty() || fired.findByLinkIdAndTriggerType(link.getId(), event).isPresent()) {
            return false;
        }
        FiredTrigger trigger = new FiredTrigger(link, event);
        trigger.setFiredAt(now);
        fired.saveAndFlush(trigger);
        enqueue(link, event, visitOrNull, now, details);
        return true;
    }
}
