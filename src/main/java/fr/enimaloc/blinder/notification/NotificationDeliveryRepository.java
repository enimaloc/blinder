package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            DeliveryStatus status, Instant before);

    List<NotificationDelivery> findTop100ByOwnerOrderByCreatedAtDesc(User owner);

    Optional<NotificationDelivery> findByIdAndOwner(UUID id, User owner);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NotificationDelivery d where d.status <> :status and d.updatedAt < :before")
    int deleteByStatusNotAndUpdatedAtBefore(@Param("status") DeliveryStatus status, @Param("before") Instant before);
}
