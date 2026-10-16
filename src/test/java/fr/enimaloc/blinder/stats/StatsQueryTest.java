package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestData.class)
class StatsQueryTest {

    @Autowired
    StatsQuery query;
    @Autowired
    TestData data;
    @Autowired
    VisitDailyStatRepository dailyStats;
    @Autowired
    BlinderProperties blinder;
    @Autowired
    Clock clock;

    User admin;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        link = data.link(data.domain("go.example.com", admin), "abc", "https://example.com");
    }

    @Test
    void dailyHasOnePointPerDayWithZeros() {
        LocalDate today = LocalDate.now(clock.withZone(blinder.admin().timeZone()));
        VisitDailyStat redirected = new VisitDailyStat(link, admin, today, VisitOutcome.REDIRECTED);
        redirected.increment(3);
        VisitDailyStat denied = new VisitDailyStat(link, admin, today, VisitOutcome.DENIED);
        denied.increment(2);
        VisitDailyStat previewed = new VisitDailyStat(link, admin, today, VisitOutcome.PREVIEWED);
        previewed.increment(1);
        dailyStats.saveAll(List.of(redirected, denied, previewed));

        List<StatsQuery.DayPoint> points = query.daily(admin, null, StatsPeriod.D7);

        assertThat(points).hasSize(7);
        assertThat(points.getFirst()).isEqualTo(new StatsQuery.DayPoint(today.minusDays(6), 0, 0, 0));
        assertThat(points.getLast()).isEqualTo(new StatsQuery.DayPoint(today, 3, 2, 1));
    }

    @Test
    void totalsByOutcomeUniqueIpsAndOwnerIsolation() {
        Instant now = clock.instant().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.DENIED, now, "203.0.113.2");
        data.visit(link, VisitOutcome.REDIRECTED, now.minus(Duration.ofDays(40)), "203.0.113.9");
        User other = data.admin("other");
        Link otherLink = data.link(data.domain("other.example.com", other), "zzz", "https://example.com");
        data.visit(otherLink, VisitOutcome.REDIRECTED, now, "203.0.113.50");

        StatsQuery.Totals totals = query.totals(admin, null, StatsPeriod.D30);

        assertThat(totals.total()).isEqualTo(3);
        assertThat(totals.byOutcome()).containsEntry(VisitOutcome.REDIRECTED, 2L).containsEntry(VisitOutcome.DENIED, 1L);
        assertThat(totals.uniqueIps()).isEqualTo(2);
        assertThat(totals.bots()).isZero();
        assertThat(query.totals(admin, link.getId(), StatsPeriod.D365).total()).isEqualTo(4);
        assertThat(query.totals(admin, otherLink.getId(), StatsPeriod.D30).total()).isZero();
        assertThat(query.topLinks(admin, StatsPeriod.D30)).extracting(StatsQuery.Count::label)
                .containsExactly("go.example.com/abc");
    }

    @Test
    void topsOrderedAndNullLabelled() {
        Instant now = clock.instant().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.2");

        assertThat(query.topCountries(admin, null, StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Inconnu", 2));
        assertThat(query.topReferers(admin, null, StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Direct", 2));
        assertThat(query.browsers(admin, null, StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Chrome", 2));
    }
}
