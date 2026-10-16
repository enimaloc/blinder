package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestData.class)
class ExpiryScannerTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    TestData data;
    @Autowired
    ExpiryScanner scanner;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkNotificationSettingsRepository settingsRepo;
    @Autowired
    NotificationDeliveryRepository deliveries;

    User admin;
    Domain domain;
    NotificationChannel hook;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        hook = data.channel(admin, "hook", ChannelType.WEBHOOK, "https://example.com/h");
    }

    Link link(String slug, boolean notify, java.util.function.Consumer<Link> config) {
        Link l = new Link(domain, slug, "https://example.com/t", admin);
        config.accept(l);
        l = data.save(l);
        LinkNotificationSettings s = new LinkNotificationSettings(links.getReferenceById(l.getId()));
        s.setOnExpired(notify);
        s.getChannels().add(hook);
        settingsRepo.saveAndFlush(s);
        return l;
    }

    @Test
    void expiredLinkNotifiesOnceAcrossScans() {
        link("old", true, l -> l.setExpiresAt(NOW.minusSeconds(5)));
        link("fresh", true, l -> l.setExpiresAt(NOW.plusSeconds(3600)));
        link("forever", true, l -> { });
        scanner.scan(NOW);
        scanner.scan(NOW.plusSeconds(60));
        assertThat(deliveries.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getEvent()).isEqualTo(TriggerType.EXPIRED);
            assertThat(d.getLinkLabel()).isEqualTo("go.example.com/old");
            assertThat(d.getPayload()).contains("a expiré");
        });
    }

    @Test
    void ttlAfterFirstUseExpiry() {
        link("ttl", true, l -> {
            l.setFirstUsedAt(NOW.minus(Duration.ofHours(2)));
            l.setTtlAfterFirstUse(Duration.ofHours(1));
        });
        link("ttl-running", true, l -> {
            l.setFirstUsedAt(NOW.minus(Duration.ofMinutes(10)));
            l.setTtlAfterFirstUse(Duration.ofHours(1));
        });
        link("ttl-unused", true, l -> l.setTtlAfterFirstUse(Duration.ofHours(1)));
        scanner.scan(NOW);
        assertThat(deliveries.findAll()).singleElement()
                .satisfies(d -> assertThat(d.getLinkLabel()).isEqualTo("go.example.com/ttl"));
    }

    @Test
    void linksNotAskingForExpiryNotificationsAreSkipped() {
        link("quiet", false, l -> l.setExpiresAt(NOW.minusSeconds(5)));
        scanner.scan(NOW);
        assertThat(deliveries.count()).isZero();
    }
}
