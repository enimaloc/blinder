package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceService {

    static final Duration ENROLLMENT_TTL = Duration.ofMinutes(15);

    private final DeviceRepository devices;
    private final DeviceEnrollmentRepository enrollments;
    private final DeviceCredentialRepository credentials;
    private final DomainRepository domains;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<DeviceRow> list(User owner) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : credentials.countByDeviceForOwner(owner)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return devices.findAllByOwnerOrderByName(owner).stream()
                .map(device -> new DeviceRow(device, counts.getOrDefault(device.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Device> all(User owner) {
        return devices.findAllByOwnerOrderByName(owner);
    }

    @Transactional(readOnly = true)
    public List<DeviceEnrollment> pendingEnrollments(User owner) {
        return enrollments.findPending(owner, clock.instant());
    }

    @Transactional
    public CreatedEnrollment createEnrollment(User owner, EnrollmentForm form) {
        Domain start = domains.findByIdAndOwner(form.getStartDomainId(), owner)
                .filter(Domain::isEnabled)
                .orElseThrow(() -> new FieldErrorException("startDomainId", "Domaine inconnu ou désactivé"));
        String token = DeviceTokens.newToken();
        DeviceEnrollment enrollment = enrollments.save(new DeviceEnrollment(owner, start, form.getName().trim(),
                DeviceTokens.sha256(token), clock.instant().plus(ENROLLMENT_TTL)));
        return new CreatedEnrollment(enrollment, start.getHost(), token);
    }

    @Transactional
    public void rename(User owner, UUID id, String name) {
        if (name == null || name.isBlank() || name.trim().length() > 64) {
            throw new FieldErrorException("name", "1 à 64 caractères");
        }
        get(owner, id).setName(name.trim());
    }

    /** Supprime l'appareil : ses secrets et ses autorisations de liens partent en cascade (base). */
    @Transactional
    public void revoke(User owner, UUID id) {
        devices.delete(get(owner, id));
    }

    @Transactional
    public void cancelEnrollment(User owner, UUID id) {
        enrollments.delete(enrollments.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Device get(User owner, UUID id) {
        return devices.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
