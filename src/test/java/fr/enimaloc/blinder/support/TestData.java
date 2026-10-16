package fr.enimaloc.blinder.support;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceCredential;
import fr.enimaloc.blinder.device.DeviceCredentialRepository;
import fr.enimaloc.blinder.device.DeviceEnrollment;
import fr.enimaloc.blinder.device.DeviceEnrollmentRepository;
import fr.enimaloc.blinder.device.DeviceRepository;
import fr.enimaloc.blinder.device.DeviceTokens;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.Role;
import fr.enimaloc.blinder.user.User;
import fr.enimaloc.blinder.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@TestComponent
@RequiredArgsConstructor
public class TestData {

    public static final String PASSWORD = "correct-horse-battery";

    private final UserRepository users;
    private final DomainRepository domains;
    private final LinkRepository links;
    private final DeviceRepository devices;
    private final DeviceCredentialRepository deviceCredentials;
    private final DeviceEnrollmentRepository deviceEnrollments;
    private final TransactionTemplate transactions;

    public void reset() {
        deviceCredentials.deleteAllInBatch();
        deviceEnrollments.deleteAllInBatch();
        links.deleteAllInBatch();
        devices.deleteAllInBatch();
        domains.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    public User admin(String username) {
        return users.save(new User(username, "{noop}" + PASSWORD, Role.ADMIN));
    }

    public Domain domain(String host, User owner) {
        return domains.save(new Domain(host, owner));
    }

    public Link link(Domain domain, String slug, String targetUrl) {
        return links.save(new Link(domain, slug, targetUrl, domain.getOwner()));
    }

    public Link save(Link link) {
        return links.save(link);
    }

    public Device device(User owner, String name) {
        return devices.save(new Device(owner, name));
    }

    /** Crée la credential (appareil, domaine) et renvoie la valeur du cookie {@code blinder_device}. */
    public String credential(Device device, Domain domain) {
        String secret = DeviceTokens.newToken();
        deviceCredentials.saveAndFlush(new DeviceCredential(device, domain, DeviceTokens.sha256(secret)));
        return device.getId() + "." + secret;
    }

    /** Crée un enrôlement et renvoie le jeton brut. */
    public String enrollment(User owner, Domain startDomain, String name, Instant expiresAt) {
        String token = DeviceTokens.newToken();
        deviceEnrollments.save(new DeviceEnrollment(owner, startDomain, name, DeviceTokens.sha256(token), expiresAt));
        return token;
    }

    public void allowDevice(Link link, Device device) {
        transactions.executeWithoutResult(status -> {
            Link managed = links.findById(link.getId()).orElseThrow();
            managed.getDevices().add(devices.getReferenceById(device.getId()));
            managed.setDeviceProtected(true);
        });
    }
}
