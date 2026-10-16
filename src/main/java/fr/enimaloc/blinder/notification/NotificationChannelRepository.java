package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    List<NotificationChannel> findByOwnerOrderByNameAsc(User owner);

    Optional<NotificationChannel> findByIdAndOwner(UUID id, User owner);

    List<NotificationChannel> findByOwnerAndPreselectedTrue(User owner);
}
