package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkNotificationSettingsRepository extends JpaRepository<LinkNotificationSettings, UUID> {

    @Query("select s from LinkNotificationSettings s where s.link.id = :linkId")
    Optional<LinkNotificationSettings> findByLinkId(@Param("linkId") UUID linkId);

    @Query("select s from LinkNotificationSettings s left join fetch s.channels where s.link.id = :linkId")
    Optional<LinkNotificationSettings> findWithChannelsByLinkId(@Param("linkId") UUID linkId);

    @Query("select distinct s from LinkNotificationSettings s left join fetch s.channels"
            + " where s.link.id in :linkIds and s.link.owner = :owner")
    List<LinkNotificationSettings> findWithChannelsByOwnerAndLinkIdIn(@Param("owner") User owner,
                                                                      @Param("linkIds") Collection<UUID> linkIds);

    /** Liens dont l'expiration doit être notifiée et ne l'a pas déjà été. */
    @Query("select s from LinkNotificationSettings s join fetch s.link where s.onExpired = true "
            + "and not exists (select 1 from FiredTrigger f where f.link = s.link "
            + "and f.triggerType = fr.enimaloc.blinder.notification.TriggerType.EXPIRED)")
    List<LinkNotificationSettings> findByOnExpiredTrue();

    /** Liens épuisés dont l'épuisement doit être notifié et ne l'a pas déjà été. */
    @Query("select s from LinkNotificationSettings s join fetch s.link l where s.onExhausted = true "
            + "and l.maxUses is not null and l.useCount >= l.maxUses "
            + "and not exists (select 1 from FiredTrigger f where f.link = s.link "
            + "and f.triggerType = fr.enimaloc.blinder.notification.TriggerType.EXHAUSTED)")
    List<LinkNotificationSettings> findExhaustedPending();

    /** Liens ayant atteint leur seuil de visites sans que l'alerte soit partie. */
    @Query("select s from LinkNotificationSettings s join fetch s.link l where s.visitThreshold is not null "
            + "and l.useCount >= s.visitThreshold "
            + "and not exists (select 1 from FiredTrigger f where f.link = s.link "
            + "and f.triggerType = fr.enimaloc.blinder.notification.TriggerType.THRESHOLD)")
    List<LinkNotificationSettings> findThresholdPending();
}
