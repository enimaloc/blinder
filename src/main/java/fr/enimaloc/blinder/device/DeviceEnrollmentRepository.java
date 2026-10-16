package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceEnrollmentRepository extends JpaRepository<DeviceEnrollment, UUID> {

    @EntityGraph(attributePaths = {"owner", "startDomain"})
    Optional<DeviceEnrollment> findByTokenHash(String tokenHash);

    Optional<DeviceEnrollment> findByIdAndOwner(UUID id, User owner);

    @EntityGraph(attributePaths = "startDomain")
    @Query("""
            select e from DeviceEnrollment e
             where e.owner = :owner and e.usedAt is null and e.expiresAt > :now
             order by e.createdAt
            """)
    List<DeviceEnrollment> findPending(@Param("owner") User owner, @Param("now") Instant now);

    @Query("""
            select case when count(e) > 0 then true else false end from DeviceEnrollment e
             where e.startDomain = :domain and e.usedAt is null and e.expiresAt > :now
            """)
    boolean hasPending(@Param("domain") Domain domain, @Param("now") Instant now);

    /** Consommation atomique : 1 si le jeton était valide, 0 sinon. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeviceEnrollment e set e.usedAt = :now
             where e.tokenHash = :hash and e.usedAt is null and e.expiresAt > :now
            """)
    int consume(@Param("hash") String hash, @Param("now") Instant now);
}
