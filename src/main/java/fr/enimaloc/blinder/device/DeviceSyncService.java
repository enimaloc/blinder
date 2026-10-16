package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.common.PublicUrls;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chaîne de redirections qui pose le cookie de l'appareil sur chaque domaine actif du propriétaire.
 * Chaque saut consomme son ticket et en émet un nouveau (rotation atomique) : une URL ne sert qu'une fois.
 */
@Service
@RequiredArgsConstructor
public class DeviceSyncService {

    static final Duration TICKET_TTL = Duration.ofMinutes(2);

    private final DeviceRepository devices;
    private final DomainRepository domains;
    private final DeviceCredentialRepository credentials;
    private final Clock clock;

    public record Hop(Device device, Domain domain, Optional<String> nextUrl) {
    }

    /** Lance (ou relance) la chaîne depuis le domaine d'origine ; vide s'il n'y a aucun autre domaine. */
    @Transactional
    public Optional<String> begin(UUID deviceId, UUID originDomainId, String scheme, int port) {
        Device device = devices.findById(deviceId).orElseThrow();
        List<Domain> targets = targets(device, originDomainId);
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        String ticket = DeviceTokens.newToken();
        devices.setTicket(deviceId, DeviceTokens.sha256(ticket), clock.instant().plus(TICKET_TTL));
        return Optional.of(hopUrl(scheme, targets.getFirst().getHost(), port, deviceId, originDomainId, ticket));
    }

    /** Un saut sur {@code host} ; vide si le ticket, l'appareil ou le domaine ne sont pas valides. */
    @Transactional
    public Optional<Hop> hop(UUID deviceId, UUID originDomainId, String ticket, String host, String scheme, int port) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Optional<Device> found = devices.findById(deviceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Device device = found.get();
        List<Domain> targets = targets(device, originDomainId);
        String normalizedHost = HostNormalizer.normalize(host);
        int index = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getHost().equals(normalizedHost)) {
                index = i;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        String current = DeviceTokens.sha256(ticket);
        boolean last = index == targets.size() - 1;
        String next = last ? null : DeviceTokens.newToken();
        int updated = last
                ? devices.clearTicket(deviceId, current, now)
                : devices.rotateTicket(deviceId, current, DeviceTokens.sha256(next), now.plus(TICKET_TTL), now);
        if (updated != 1) {
            return Optional.empty();
        }
        Optional<String> nextUrl = last
                ? Optional.empty()
                : Optional.of(hopUrl(scheme, targets.get(index + 1).getHost(), port, deviceId, originDomainId, next));
        return Optional.of(new Hop(device, targets.get(index), nextUrl));
    }

    @Transactional(readOnly = true)
    public long syncedDomains(UUID deviceId) {
        return credentials.countByDeviceId(deviceId);
    }

    /** Domaines actifs du propriétaire, triés par host, hors domaine d'origine. */
    private List<Domain> targets(Device device, UUID originDomainId) {
        return domains.findAllByOwnerAndEnabledTrueOrderByHost(device.getOwner()).stream()
                .filter(domain -> !domain.getId().equals(originDomainId))
                .toList();
    }

    private static String hopUrl(String scheme, String host, int port, UUID deviceId, UUID originDomainId,
                                 String ticket) {
        return PublicUrls.base(scheme, host, port) + "/_/device/sync?d=" + deviceId + "&o=" + originDomainId
                + "&t=" + ticket;
    }
}
