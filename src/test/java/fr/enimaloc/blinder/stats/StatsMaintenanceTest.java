package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                new StatsProperties(retention, 100, Duration.ofSeconds(1)), clock);
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
}
