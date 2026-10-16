package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.mockito.AdditionalAnswers;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

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
        return writer(links, capacity, geo, listeners);
    }

    VisitWriter writer(LinkRepository linkRepository, int capacity, GeoLocator geo, VisitBatchListener... listeners) {
        return writer(linkRepository, tx, capacity, geo, listeners);
    }

    VisitWriter writer(LinkRepository linkRepository, TransactionTemplate transactions, int capacity, GeoLocator geo,
                       VisitBatchListener... listeners) {
        ObjectProvider<VisitBatchListener> provider = new ObjectProvider<>() {
            @Override
            public Stream<VisitBatchListener> orderedStream() {
                return Stream.of(listeners);
            }

            @Override
            public Stream<VisitBatchListener> stream() {
                return orderedStream();
            }
        };
        return new VisitWriter(linkRepository, visits, stats, transactions, new StatsProperties(Duration.ofDays(90), capacity,
                Duration.ofMillis(50), false), blinder, geo, provider);
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
    void linkDeletedDuringTheBatchOnlyLosesItsOwnVisits() {
        Link doomed = data.link(link.getDomain(), "doomed", "https://example.com/d");
        Link loaded = links.findById(doomed.getId()).orElseThrow();
        // Le lien est trouvé au début du lot puis supprimé avant la validation : la clé étrangère fait échouer le lot.
        LinkRepository racing = mock(LinkRepository.class, AdditionalAnswers.delegatesTo(links));
        doReturn(Optional.of(loaded)).doReturn(Optional.empty()).when(racing).findById(doomed.getId());
        tx.executeWithoutResult(status -> links.deleteById(doomed.getId()));
        List<Visit> received = new ArrayList<>();
        VisitWriter w = writer(racing, 10, new NoopGeoLocator(), received::addAll);
        w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
        w.offer(record(doomed.getId(), Instant.now(), "1.1.1.2"));
        w.offer(record(link.getId(), Instant.now(), "1.1.1.3"));

        assertThatCode(w::flush).doesNotThrowAnyException();

        assertThat(visits.findAll()).extracting(Visit::getIp).containsExactlyInAnyOrder("1.1.1.1", "1.1.1.3");
        assertThat(stats.findAll()).singleElement().satisfies(s -> assertThat(s.getVisits()).isEqualTo(2));
        assertThat(received).extracting(Visit::getIp).containsExactlyInAnyOrder("1.1.1.1", "1.1.1.3");
        // Les visites d'un lien supprimé ne sont pas « perdues » : elles auraient disparu avec lui.
        assertThat(w.dropped()).isZero();
    }

    @Test
    void databaseOutageLogsOneTracePerBatchAndDropsItWhole() {
        AtomicInteger attempts = new AtomicInteger();
        TransactionTemplate down = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                attempts.incrementAndGet();
                throw new DataAccessResourceFailureException("base indisponible");
            }
        };
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(VisitWriter.class);
        logs.start();
        logger.addAppender(logs);
        try {
            VisitWriter w = writer(links, down, 10, new NoopGeoLocator());
            for (int i = 0; i < 5; i++) {
                w.offer(record(link.getId(), Instant.now(), "1.1.1." + i));
            }
            assertThatCode(w::flush).doesNotThrowAnyException();

            assertThat(attempts).hasValue(1);
            assertThat(w.dropped()).isEqualTo(5);
            List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).singleElement().satisfies(e -> {
                assertThat(e.getFormattedMessage()).contains("lot de 5 visites");
                assertThat(e.getThrowableProxy()).isNotNull();
            });
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void connectionFailureIsTriedOnceDropsTheWholeBatchAndDoesNotStallTheWriter() {
        // Premier appel : pas de connexion (ce qui prendrait connectionTimeout par visite si on réessayait une à une).
        AtomicInteger attempts = new AtomicInteger();
        TransactionTemplate flaky = new TransactionTemplate(tx.getTransactionManager()) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                if (attempts.incrementAndGet() == 1) {
                    throw new CannotCreateTransactionException("pas de connexion");
                }
                return super.execute(action);
            }
        };
        VisitWriter w = writer(links, flaky, 10, new NoopGeoLocator());
        for (int i = 0; i < 4; i++) {
            w.offer(record(link.getId(), Instant.now(), "1.1.1." + i));
        }
        assertThatCode(w::flush).doesNotThrowAnyException();

        assertThat(attempts).hasValue(1);
        assertThat(w.dropped()).isEqualTo(4);
        assertThat(visits.count()).isZero();

        // Le lot suivant passe normalement.
        w.offer(record(link.getId(), Instant.now(), "2.2.2.2"));
        w.flush();
        assertThat(attempts).hasValue(2);
        assertThat(visits.findAll()).singleElement().satisfies(v -> assertThat(v.getIp()).isEqualTo("2.2.2.2"));
        assertThat(w.dropped()).isEqualTo(4);
    }

    @Test
    void aVisitThatCannotBeEnrichedIsTheOnlyOneLost() {
        GeoLocator picky = ip -> {
            if (ip.equals("6.6.6.6")) {
                throw new IllegalStateException("boom");
            }
            return Optional.empty();
        };
        VisitWriter w = writer(10, picky);
        w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
        w.offer(record(link.getId(), Instant.now(), "6.6.6.6"));
        w.offer(record(link.getId(), Instant.now(), "1.1.1.3"));
        w.flush();
        assertThat(visits.findAll()).extracting(Visit::getIp).containsExactlyInAnyOrder("1.1.1.1", "1.1.1.3");
        assertThat(w.dropped()).isEqualTo(1);
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
    void backgroundThreadWritesAndStopFlushes() throws InterruptedException {
        VisitWriter w = writer(10, new NoopGeoLocator());
        w.start();
        try {
            w.offer(record(link.getId(), Instant.now(), "1.1.1.1"));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (visits.count() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(visits.count()).isEqualTo(1);
        } finally {
            w.stop();
        }
        assertThat(visits.count()).isEqualTo(1);
        assertThat(w.isRunning()).isFalse();
    }
}
