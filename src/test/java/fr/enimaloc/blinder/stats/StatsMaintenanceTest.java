package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.DeliveryStatus;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationDelivery;
import fr.enimaloc.blinder.notification.TriggerType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.util.UUID;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestData.class)
class StatsMaintenanceTest {

    static final Instant NOW = Instant.parse("2026-10-16T03:30:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    TestData data;
    @Autowired
    VisitRepository visits;
    @Autowired
    NotificationDeliveryRepository deliveries;

    @Autowired
    JdbcTemplate jdbc;

    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        link = data.link(data.domain("go.example.com", data.admin("admin")), "plain", "https://example.com/p");
        data.visit(link, VisitOutcome.REDIRECTED, NOW.minus(Duration.ofDays(91)), "1.1.1.1");
        data.visit(link, VisitOutcome.REDIRECTED, NOW.minus(Duration.ofDays(89)), "1.1.1.2");
    }

    StatsMaintenance maintenance(Duration retention) {
        return new StatsMaintenance(visits, deliveries,
                new StatsProperties(retention, 100, Duration.ofSeconds(1), false), clock);
    }

    @Test
    void purgeKeepsOnlyRecentVisits() {
        maintenance(Duration.ofDays(90)).purge();
        assertThat(visits.findAll()).singleElement().satisfies(v -> assertThat(v.getIp()).isEqualTo("1.1.1.2"));
    }

    @Test
    void zeroRetentionKeepsEverything() {
        maintenance(Duration.ZERO).purge();
        assertThat(visits.count()).isEqualTo(2);
    }

    NotificationDelivery delivery(NotificationChannel channel, DeliveryStatus status, Duration age) {
        NotificationDelivery d = new NotificationDelivery(link.getOwner(), channel, link.getId(), "go/plain",
                TriggerType.TEST, "{}", NOW);
        d.setStatus(status);
        d = deliveries.saveAndFlush(d);
        jdbc.update("update notification_deliveries set updated_at = ? where id = ?",
                Timestamp.from(NOW.minus(age)), d.getId().toString());
        return d;
    }

    @Test
    void purgeDeletesOnlyOldFinishedDeliveries() {
        NotificationChannel channel = data.channel(link.getOwner(), "hook", ChannelType.WEBHOOK, "https://example.com/h");
        UUID pending = delivery(channel, DeliveryStatus.PENDING, Duration.ofDays(40)).getId();
        UUID oldSent = delivery(channel, DeliveryStatus.SENT, Duration.ofDays(40)).getId();
        UUID oldFailed = delivery(channel, DeliveryStatus.FAILED, Duration.ofDays(40)).getId();
        UUID recentSent = delivery(channel, DeliveryStatus.SENT, Duration.ofDays(5)).getId();
        maintenance(Duration.ofDays(90)).purge();
        assertThat(deliveries.findAll()).extracting(NotificationDelivery::getId)
                .containsExactlyInAnyOrder(pending, recentSent)
                .doesNotContain(oldSent, oldFailed);
    }

    @Test
    void purgeRemovesOldVisitsAcrossSeveralBatches() {
        for (int i = 0; i < 4; i++) {
            data.visit(link, VisitOutcome.REDIRECTED, NOW.minus(Duration.ofDays(100 + i)), "2.2.2." + i);
        }
        // 5 visites anciennes (dont celle du setUp), lots de 2 : 3 passages
        int removed = maintenance(Duration.ofDays(90)).purgeVisits(NOW.minus(Duration.ofDays(90)), 2);
        assertThat(removed).isEqualTo(5);
        assertThat(visits.findAll()).singleElement().satisfies(v -> assertThat(v.getIp()).isEqualTo("1.1.1.2"));
    }
}
