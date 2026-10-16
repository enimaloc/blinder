package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.domain.Domain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceEnrollmentService {

    private final DeviceEnrollmentRepository enrollments;
    private final DeviceRepository devices;
    private final Clock clock;

    public record Enrolled(Device device, Domain domain) {
    }

    /** Enrôlement en attente valable pour ce host (écran de confirmation : ne consomme rien). */
    @Transactional(readOnly = true)
    public Optional<DeviceEnrollment> pending(String token, String host) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        String normalizedHost = HostNormalizer.normalize(host);
        return enrollments.findByTokenHash(DeviceTokens.sha256(token))
                .filter(enrollment -> enrollment.getUsedAt() == null && enrollment.getExpiresAt().isAfter(now))
                .filter(enrollment -> enrollment.getStartDomain().isEnabled()
                        && enrollment.getStartDomain().getHost().equals(normalizedHost));
    }

    /** Consomme le jeton (atomique) et crée l'appareil ; vide si invalide, expiré, déjà utilisé ou mauvais host. */
    @Transactional
    public Optional<Enrolled> complete(String token, String host) {
        Optional<DeviceEnrollment> enrollment = pending(token, host);
        if (enrollment.isEmpty()) {
            return Optional.empty();
        }
        DeviceEnrollment found = enrollment.get();
        if (enrollments.consume(found.getTokenHash(), clock.instant()) != 1) {
            return Optional.empty();
        }
        Device device = devices.save(new Device(found.getOwner(), found.getName()));
        return Optional.of(new Enrolled(device, found.getStartDomain()));
    }
}
