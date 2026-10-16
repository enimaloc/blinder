package fr.enimaloc.blinder.notification;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Envoie les notifications dues. Les lectures et écritures sont de courtes transactions ; l'expédition
 * (HTTP, SMTP) se fait hors transaction pour ne pas garder de connexion à la base.
 */
@Slf4j
@Component
public class DeliveryDispatcher {

    static final int MAX_ATTEMPTS = 5;
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2));

    private final NotificationDeliveryRepository deliveries;
    private final Map<ChannelType, ChannelSender> senders = new EnumMap<>(ChannelType.class);
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public DeliveryDispatcher(NotificationDeliveryRepository deliveries, List<ChannelSender> senders,
                              TransactionTemplate transactions, Clock clock) {
        this.deliveries = deliveries;
        senders.forEach(sender -> this.senders.put(sender.type(), sender));
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    void dispatch() {
        dispatch(Instant.now(clock));
    }

    public void dispatch(Instant now) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<NotificationDelivery> due = transactions.execute(status -> {
                List<NotificationDelivery> found = deliveries
                        .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(DeliveryStatus.PENDING, now);
                // le canal est chargé ici : il sera lu hors transaction
                found.forEach(d -> Hibernate.initialize(d.getChannel()));
                return found;
            });
            if (due == null) {
                return;
            }
            for (NotificationDelivery delivery : due) {
                try {
                    boolean disabled = !delivery.getChannel().isEnabled();
                    // canal désactivé : échec définitif, pas de réessai
                    record(delivery, disabled ? SendResult.failure("Canal désactivé") : send(delivery), disabled);
                } catch (RuntimeException e) {
                    log.error("Envoi {} en échec inattendu", delivery.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Répartition des notifications en échec", e);
        } finally {
            running.set(false);
        }
    }

    private SendResult send(NotificationDelivery delivery) {
        NotificationChannel channel = delivery.getChannel();
        ChannelSender sender = senders.get(channel.getType());
        if (sender == null) {
            return SendResult.failure("Type de canal non géré");
        }
        try {
            return sender.send(channel, delivery);
        } catch (RuntimeException e) {
            return SendResult.failure(e);
        }
    }

    private void record(NotificationDelivery sent, SendResult result, boolean permanent) {
        // l'envoi a pu durer : l'heure d'envoi et le délai de réessai partent de maintenant, pas du début du lot
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> deliveries.findById(sent.getId()).ifPresent(delivery -> {
            if (delivery.getStatus() != DeliveryStatus.PENDING) {
                return;
            }
            if (result.success()) {
                delivery.setStatus(DeliveryStatus.SENT);
                delivery.setSentAt(now);
                delivery.setLastError(null);
                delivery.setNextAttemptAt(null);
                return;
            }
            int attempts = delivery.getAttempts() + 1;
            delivery.setAttempts(attempts);
            delivery.setLastError(SendResult.truncate(result.error()));
            if (permanent || attempts >= MAX_ATTEMPTS) {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setNextAttemptAt(null);
            } else {
                delivery.setNextAttemptAt(now.plus(BACKOFF.get(attempts - 1)));
            }
        }));
    }
}
