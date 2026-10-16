package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = "blinder.admin.time-zone=Europe/Paris")
@Import(TestData.class)
class VisitWriterTest {

    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    VisitRepository visits;
    @Autowired
    VisitDailyStatRepository stats;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    BlinderProperties blinder;

    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        Domain domain = data.domain("go.example.com", data.admin("admin"));
        link = data.link(domain, "plain", "https://example.com/p");
    }

    VisitWriter writer(int capacity, GeoLocator geo, VisitBatchListener... listeners) {
        ObjectProvider<VisitBatchListener> provider = new ObjectProvider<>() {
            @Override
            public java.util.stream.Stream<VisitBatchListener> orderedStream() {
                return java.util.stream.Stream.of(listeners);
            }

            @Override
            public java.util.stream.Stream<VisitBatchListener> stream() {
                return orderedStream();
            }
        };
        return new VisitWriter(links, visits, stats, tx, new StatsProperties(Duration.ofDays(90), capacity,
                Duration.ofMillis(50)), blinder, geo, provider);
    }

    VisitRecord record(UUID linkId, Instant at, String ip) {
        return new VisitRecord(linkId, at, VisitOutcome.REDIRECTED, null, "GET", false, ip, "UA", null);
    }

    @Test
    void overflowDropsInsteadOfBlocking() {
        VisitWriter w = writer(2, new NoopGeoLocator());
        assertThatCode(() -> {
            w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
            w.offer(record(link.getId(), Instant.now(), "1.1.1.2"));
            assertThat(w.offer(record(link.getId(), Instant.now(), "1.1.1.3"))).isFalse();
        }).doesNotThrowAnyException();
        assertThat(w.dropped()).isEqualTo(1);
    }

    @Test
    void threeVisitsSameDayGiveOneCounterRow() {
        VisitWriter w = writer(10, new NoopGeoLocator());
        Instant at = Instant.parse("2026-10-16T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            w.offer(record(link.getId(), at.plusSeconds(i), "1.1.1." + i));
        }
        w.flush();
        assertThat(visits.count()).isEqualTo(3);
        assertThat(stats.findAll()).singleElement().satisfies(s -> {
            assertThat(s.getVisits()).isEqualTo(3);
            assertThat(s.getOutcome()).isEqualTo(VisitOutcome.REDIRECTED);
        });
    }

    @Test
    void dayFollowsConfiguredTimeZone() {
        VisitWriter w = writer(10, new NoopGeoLocator());
        w.offer(record(link.getId(), Instant.parse("2026-10-15T23:30:00Z"), "1.1.1.1"));
        w.flush();
        assertThat(stats.findAll()).singleElement()
                .satisfies(s -> assertThat(s.getDay()).isEqualTo(LocalDate.of(2026, 10, 16)));
    }

    @Test
    void deletedLinkIsIgnored() {
        VisitWriter w = writer(10, new NoopGeoLocator());
        w.offer(record(UUID.randomUUID(), Instant.now(), "1.1.1.1"));
        w.offer(record(link.getId(), Instant.now(), "1.1.1.2"));
        assertThatCode(w::flush).doesNotThrowAnyException();
        assertThat(visits.findAll()).singleElement().satisfies(v -> assertThat(v.getIp()).isEqualTo("1.1.1.2"));
        assertThat(w.dropped()).isZero();
    }

    @Test
    void failingBatchIsDroppedAndTheNextOneIsWritten() {
        GeoLocator flaky = new GeoLocator() {
            boolean first = true;

            @Override
            public Optional<GeoLocation> locate(String ip) {
                if (first) {
                    first = false;
                    throw new IllegalStateException("boom");
                }
                return Optional.empty();
            }
        };
        VisitWriter w = writer(10, flaky);
        w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
        assertThatCode(w::flush).doesNotThrowAnyException();
        assertThat(w.dropped()).isEqualTo(1);
        w.offer(record(link.getId(), Instant.now(), "1.1.1.2"));
        w.flush();
        assertThat(visits.findAll()).singleElement().satisfies(v -> assertThat(v.getIp()).isEqualTo("1.1.1.2"));
    }

    @Test
    void listenersReceivePersistedVisitsAndCannotBreakTheWriter() {
        List<Visit> received = new ArrayList<>();
        VisitWriter w = writer(10, new NoopGeoLocator(), v -> {
            throw new IllegalStateException("listener");
        }, received::addAll);
        w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
        assertThatCode(w::flush).doesNotThrowAnyException();
        assertThat(received).hasSize(1);
        assertThat(w.dropped()).isZero();
    }

    @Test
    void backgroundThreadWritesAndStopFlushes() {
        VisitWriter w = writer(10, new NoopGeoLocator());
        w.start();
        try {
            w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
        } finally {
            w.stop();
        }
        assertThat(visits.count()).isEqualTo(1);
        assertThat(w.isRunning()).isFalse();
    }
}
