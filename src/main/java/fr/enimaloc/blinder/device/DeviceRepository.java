package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findAllByOwnerOrderByName(User owner);

    Optional<Device> findByIdAndOwner(UUID id, User owner);

    List<Device> findAllByIdInAndOwner(Collection<UUID> ids, User owner);

    boolean existsByOwner(User owner);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Device d set d.syncTicketHash = :hash, d.syncExpiresAt = :expiresAt where d.id = :id")
    int setTicket(@Param("id") UUID id, @Param("hash") String hash, @Param("expiresAt") Instant expiresAt);

    /** Rotation atomique : un ticket ne sert qu'une fois. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Device d set d.syncTicketHash = :next, d.syncExpiresAt = :expiresAt
             where d.id = :id and d.syncTicketHash = :current and d.syncExpiresAt > :now
            """)
    int rotateTicket(@Param("id") UUID id, @Param("current") String current, @Param("next") String next,
                     @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Device d set d.syncTicketHash = null, d.syncExpiresAt = null
             where d.id = :id and d.syncTicketHash = :current and d.syncExpiresAt > :now
            """)
    int clearTicket(@Param("id") UUID id, @Param("current") String current, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Device d set d.lastSeenAt = :now
             where d.id = :id and (d.lastSeenAt is null or d.lastSeenAt < :threshold)
            """)
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
