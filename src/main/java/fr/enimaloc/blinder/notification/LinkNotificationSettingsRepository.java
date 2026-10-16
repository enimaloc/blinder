package fr.enimaloc.blinder.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkNotificationSettingsRepository extends JpaRepository<LinkNotificationSettings, UUID> {

    @Query("select s from LinkNotificationSettings s where s.link.id = :linkId")
    Optional<LinkNotificationSettings> findByLinkId(@Param("linkId") UUID linkId);

    @Query("select s from LinkNotificationSettings s left join fetch s.channels where s.link.id = :linkId")
    Optional<LinkNotificationSettings> findWithChannelsByLinkId(@Param("linkId") UUID linkId);

    /** Liens dont l'expiration doit être notifiée et ne l'a pas déjà été. */
    @Query("select s from LinkNotificationSettings s join fetch s.link where s.onExpired = true "
            + "and not exists (select 1 from FiredTrigger f where f.link = s.link "
            + "and f.triggerType = fr.enimaloc.blinder.notification.TriggerType.EXPIRED)")
    List<LinkNotificationSettings> findByOnExpiredTrue();
}
