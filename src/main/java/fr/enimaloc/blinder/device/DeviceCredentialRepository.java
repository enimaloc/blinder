package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, UUID> {

    @Query("select c from DeviceCredential c where c.device.id = :deviceId and c.domain.id = :domainId")
    Optional<DeviceCredential> find(@Param("deviceId") UUID deviceId, @Param("domainId") UUID domainId);

    @Query("select count(c) from DeviceCredential c where c.device.id = :deviceId")
    long countByDeviceId(@Param("deviceId") UUID deviceId);

    /** Paires {@code (appareil, nombre de domaines)} des appareils du propriétaire qui en ont au moins un. */
    @Query("select c.device.id, count(c) from DeviceCredential c where c.device.owner = :owner group by c.device.id")
    List<Object[]> countByDeviceForOwner(@Param("owner") User owner);
}
