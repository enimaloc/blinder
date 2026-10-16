package fr.enimaloc.blinder.notification;

/** Expédie un envoi vers un type de canal. Ne lève jamais d'exception : renvoie un {@link SendResult}. */
public interface ChannelSender {

    ChannelType type();

    SendResult send(NotificationChannel channel, NotificationDelivery delivery);
}
