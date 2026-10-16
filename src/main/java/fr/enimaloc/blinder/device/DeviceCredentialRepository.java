package fr.enimaloc.blinder.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, UUID> {

    @Query("select c from DeviceCredential c where c.device.id = :deviceId and c.domain.id = :domainId")
    Optional<DeviceCredential> find(@Param("deviceId") UUID deviceId, @Param("domainId") UUID domainId);

    @Query("select count(c) from DeviceCredential c where c.device.id = :deviceId")
    long countByDeviceId(@Param("deviceId") UUID deviceId);
}
