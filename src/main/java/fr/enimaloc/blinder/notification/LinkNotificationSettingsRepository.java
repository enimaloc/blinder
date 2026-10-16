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

    @Query("select s from LinkNotificationSettings s join fetch s.link where s.onExpired = true")
    List<LinkNotificationSettings> findByOnExpiredTrue();
}
