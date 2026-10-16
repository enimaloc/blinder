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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import fr.enimaloc.blinder.tag.Tag;
import java.io.StringWriter;
import java.util.UUID;

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
    @Autowired
    TransactionTemplate transactions;

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

        List<StatsQuery.DayPoint> points = query.daily(admin, StatsScope.all(), StatsPeriod.D7);

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

        StatsQuery.Totals totals = query.totals(admin, StatsScope.all(), StatsPeriod.D30);

        assertThat(totals.total()).isEqualTo(3);
        assertThat(totals.byOutcome()).containsEntry(VisitOutcome.REDIRECTED, 2L).containsEntry(VisitOutcome.DENIED, 1L);
        assertThat(totals.uniqueIps()).isEqualTo(2);
        assertThat(totals.bots()).isZero();
        assertThat(query.totals(admin, StatsScope.link(link.getId()), StatsPeriod.D365).total()).isEqualTo(4);
        assertThat(query.totals(admin, StatsScope.link(otherLink.getId()), StatsPeriod.D30).total()).isZero();
        assertThat(query.topLinks(admin, StatsScope.all(), StatsPeriod.D30)).extracting(StatsQuery.Count::label)
                .containsExactly("go.example.com/abc");
    }

    @Test
    void topsOrderedAndNullLabelled() {
        Instant now = clock.instant().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.2");

        assertThat(query.topCountries(admin, StatsScope.all(), StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Inconnu", 2));
        assertThat(query.topReferers(admin, StatsScope.all(), StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Direct", 2));
        assertThat(query.browsers(admin, StatsScope.all(), StatsPeriod.D7))
                .containsExactly(new StatsQuery.Count("Chrome", 2));
    }

    @Test
    void csvIsWrittenOutsideAnyTransaction() {
        Instant now = clock.instant().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.DENIED, now, "203.0.113.2");
        List<Boolean> activeDuringWrite = new ArrayList<>();
        Writer probe = new Writer() {
            @Override
            public void write(char[] buf, int off, int len) {
                activeDuringWrite.add(TransactionSynchronizationManager.isActualTransactionActive());
            }

            @Override
            public void flush() throws IOException {
            }

            @Override
            public void close() throws IOException {
            }
        };

        // même appelé depuis une transaction, l'export la suspend
        transactions.executeWithoutResult(status -> query.exportCsv(admin, StatsScope.all(), StatsPeriod.D7, probe));

        assertThat(activeDuringWrite).hasSizeGreaterThanOrEqualTo(3).containsOnly(false);
    }

    @Test
    void tagScopeAggregatesOnlyTaggedLinks() {
        Link tagged = data.link(link.getDomain(), "tgd", "https://example.com");
        Tag promo = data.tag(admin, "promo");
        data.tagLink(tagged, promo);
        Instant now = clock.instant().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(tagged, VisitOutcome.REDIRECTED, now, "203.0.113.2");
        data.visit(tagged, VisitOutcome.DENIED, now, "203.0.113.3");
        LocalDate today = LocalDate.now(clock.withZone(blinder.admin().timeZone()));
        VisitDailyStat plain = new VisitDailyStat(link, admin, today, VisitOutcome.REDIRECTED);
        plain.increment(5);
        VisitDailyStat tg = new VisitDailyStat(tagged, admin, today, VisitOutcome.REDIRECTED);
        tg.increment(2);
        dailyStats.saveAll(List.of(plain, tg));
        StatsScope scope = StatsScope.tag(promo.getId());

        assertThat(query.daily(admin, scope, StatsPeriod.D7).getLast()).isEqualTo(new StatsQuery.DayPoint(today, 2, 0, 0));
        assertThat(query.daily(admin, StatsScope.all(), StatsPeriod.D7).getLast()).isEqualTo(new StatsQuery.DayPoint(today, 7, 0, 0));
        assertThat(query.totals(admin, scope, StatsPeriod.D30).total()).isEqualTo(2);
        assertThat(query.topLinks(admin, scope, StatsPeriod.D30)).extracting(StatsQuery.Count::label)
                .containsExactly("go.example.com/tgd");
        assertThat(query.recent(admin, scope, StatsPeriod.D30, 50)).hasSize(2);
        StringWriter csv = new StringWriter();
        query.exportCsv(admin, scope, StatsPeriod.D30, csv);
        assertThat(csv.toString().split("\r\n")).hasSize(3);
        assertThat(query.totals(admin, StatsScope.tag(UUID.randomUUID()), StatsPeriod.D30).total()).isZero();
    }

    @Test
    void tagScopeIgnoresLinksOfAnotherOwner() {
        User other = data.admin("other");
        Link foreign = data.link(data.domain("other.example.com", other), "fg", "https://example.com");
        Tag promo = data.tag(admin, "promo");
        data.tagLink(foreign, promo);
        LocalDate today = LocalDate.now(clock.withZone(blinder.admin().timeZone()));
        // Cumul incohérent (propriétaire du lien différent) : la portée par tag ne doit pas le compter.
        VisitDailyStat stray = new VisitDailyStat(foreign, admin, today, VisitOutcome.REDIRECTED);
        stray.increment(4);
        dailyStats.save(stray);

        assertThat(query.daily(admin, StatsScope.tag(promo.getId()), StatsPeriod.D7).getLast().redirected()).isZero();
    }
}
