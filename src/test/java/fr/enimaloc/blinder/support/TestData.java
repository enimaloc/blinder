package fr.enimaloc.blinder.support;

import fr.enimaloc.blinder.api.token.ApiToken;
import fr.enimaloc.blinder.api.token.ApiTokenRepository;
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
import fr.enimaloc.blinder.link.preview.LinkPreview;
import fr.enimaloc.blinder.link.preview.LinkPreviewRepository;
import fr.enimaloc.blinder.link.preview.PreviewStatus;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.FiredTriggerRepository;
import fr.enimaloc.blinder.notification.LinkNotificationSettingsRepository;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationChannelRepository;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagRepository;
import fr.enimaloc.blinder.stats.VisitDailyStatRepository;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.stats.VisitRepository;
import fr.enimaloc.blinder.stats.VisitWriter;
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
    private final LinkPreviewRepository linkPreviews;
    private final VisitRepository visits;
    private final VisitDailyStatRepository dailyStats;
    private final NotificationChannelRepository channels;
    private final LinkNotificationSettingsRepository notificationSettings;
    private final FiredTriggerRepository firedTriggers;
    private final NotificationDeliveryRepository deliveries;
    private final TagRepository tags;
    private final ApiTokenRepository apiTokens;
    private final TransactionTemplate transactions;
    private final VisitWriter visitWriter;

    public void reset() {
        visitWriter.flush();
        deliveries.deleteAllInBatch();
        firedTriggers.deleteAllInBatch();
        // deleteAll() (et non InBatch) : charge les entités pour vider la table de jointure des canaux.
        notificationSettings.deleteAll();
        channels.deleteAllInBatch();
        dailyStats.deleteAllInBatch();
        visits.deleteAllInBatch();
        linkPreviews.deleteAllInBatch();
        deviceCredentials.deleteAllInBatch();
        deviceEnrollments.deleteAllInBatch();
        apiTokens.deleteAllInBatch();
        // deleteAll() : charge les liens pour vider link_tags avant les tags.
        links.deleteAll();
        tags.deleteAllInBatch();
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

    public Tag tag(User owner, String name) {
        return tags.save(new Tag(owner, name));
    }

    public void tagLink(Link link, Tag tag) {
        transactions.executeWithoutResult(status -> {
            Link managed = links.findById(link.getId()).orElseThrow();
            managed.getTags().add(tags.getReferenceById(tag.getId()));
        });
    }

    /** Crée un jeton d'API et renvoie le jeton brut {@code bl_...}. */
    public String apiToken(User owner, String name) {
        String raw = "bl_" + DeviceTokens.newToken();
        apiTokens.save(new ApiToken(owner, name, raw.substring(0, 10), DeviceTokens.sha256(raw)));
        return raw;
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

    public LinkPreview preview(Link link, String title, String description, String imageUrl, PreviewStatus status) {
        LinkPreview preview = new LinkPreview(links.getReferenceById(link.getId()));
        preview.setTitle(title);
        preview.setDescription(description);
        preview.setImageUrl(imageUrl);
        preview.setStatus(status);
        preview.setFetchedAt(Instant.now());
        return linkPreviews.saveAndFlush(preview);
    }

    public Visit visit(Link link, VisitOutcome outcome, Instant occurredAt, String ip) {
        Link managed = links.getReferenceById(link.getId());
        return visits.saveAndFlush(new Visit(managed, link.getOwner(), occurredAt, outcome, null, "GET", false, ip,
                "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0", "Chrome", "Windows", null, null, null, null));
    }

    public NotificationChannel channel(User owner, String name, ChannelType type, String target) {
        return channels.saveAndFlush(new NotificationChannel(owner, name, type, target));
    }
}
