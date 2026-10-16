package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.stats.VisitRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verrou de déclencheur déjà pris : l'attente est bornée et l'alerte « une seule fois » n'est pas perdue. */
@SpringBootTest(properties = "blinder.notifications.trigger-lock-wait=300ms")
@Import(TestData.class)
class TriggerLockWaitTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    TestData data;
    @Autowired
    ExpiryScanner scanner;
    @Autowired
    NotificationTriggers triggers;
    @Autowired
    TriggerLocks locks;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    LinkRepository links;
    @Autowired
    VisitRepository visits;
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

    Link link(java.util.function.Consumer<Link> config, java.util.function.Consumer<LinkNotificationSettings> cfg) {
        Link l = new Link(domain, "l", "https://example.com/t", admin);
        config.accept(l);
        l = data.save(l);
        LinkNotificationSettings s = new LinkNotificationSettings(links.getReferenceById(l.getId()));
        cfg.accept(s);
        s.getChannels().add(hook);
        settingsRepo.saveAndFlush(s);
        return l;
    }

    Visit visit(Link link, VisitOutcome outcome, DenyReason reason) {
        return visits.saveAndFlush(new Visit(links.getReferenceById(link.getId()), admin, NOW, outcome, reason, "GET",
                false, "203.0.113.9", "ua", null, null, null, null, null, null));
    }

    List<NotificationDelivery> sent(TriggerType type) {
        return deliveries.findAll().stream().filter(d -> d.getEvent() == type).toList();
    }

    /** Garde le verrou (lien, type) dans une transaction ouverte jusqu'à l'appel de la tâche renvoyée. */
    private Runnable holdLock(UUID linkId, TriggerType type) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            assertThat(locks.acquireForTransaction(linkId, type)).isTrue();
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.join();
        };
    }

    @Test
    void visitWriterWaitsNoLongerThanConfiguredAndExhaustedAlertIsSentOnceLater() throws Exception {
        Link link = link(l -> {
            l.setMaxUses(1);
            l.setUseCount(1);
        }, s -> s.setOnExhausted(true));
        Visit visit = visit(link, VisitOutcome.REDIRECTED, null);

        Runnable release = holdLock(link.getId(), TriggerType.EXHAUSTED);
        long start = System.nanoTime();
        triggers.onVisits(List.of(visit));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        release.run();

        assertThat(waited).isLessThan(Duration.ofSeconds(5));
        assertThat(sent(TriggerType.EXHAUSTED)).isEmpty();

        scanner.scan(NOW);
        scanner.scan(NOW.plusSeconds(60));

        assertThat(sent(TriggerType.EXHAUSTED)).hasSize(1);
    }

    @Test
    void thresholdAlertMissedOnLockIsSentOnceLater() throws Exception {
        Link link = link(l -> l.setUseCount(3), s -> s.setVisitThreshold(3));
        Visit visit = visit(link, VisitOutcome.REDIRECTED, null);

        Runnable release = holdLock(link.getId(), TriggerType.THRESHOLD);
        triggers.onVisits(List.of(visit));
        release.run();
        assertThat(sent(TriggerType.THRESHOLD)).isEmpty();

        scanner.scan(NOW);
        scanner.scan(NOW.plusSeconds(60));

        assertThat(sent(TriggerType.THRESHOLD)).hasSize(1);
    }

    @Test
    void expiredAlertMissedOnLockIsSentOnceLater() throws Exception {
        Link link = link(l -> l.setExpiresAt(NOW.minusSeconds(5)), s -> s.setOnExpired(true));
        Visit visit = visit(link, VisitOutcome.DENIED, DenyReason.EXPIRED);

        Runnable release = holdLock(link.getId(), TriggerType.EXPIRED);
        triggers.onVisits(List.of(visit));
        release.run();
        assertThat(sent(TriggerType.EXPIRED)).isEmpty();

        scanner.scan(NOW);
        scanner.scan(NOW.plusSeconds(60));

        assertThat(sent(TriggerType.EXPIRED)).hasSize(1);
    }

    @Test
    void scannerDoesNotNotifyExhaustionOrThresholdNotReached() {
        link(l -> {
            l.setMaxUses(5);
            l.setUseCount(2);
        }, s -> {
            s.setOnExhausted(true);
            s.setVisitThreshold(3);
        });
        scanner.scan(NOW);
        assertThat(deliveries.count()).isZero();
    }
}
