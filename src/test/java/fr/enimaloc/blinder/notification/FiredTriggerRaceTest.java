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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Scanner d'expiration et écrivain de visites déclenchent EXPIRED en même temps pour le même lien. */
@SpringBootTest
@Import(TestData.class)
class FiredTriggerRaceTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    TestData data;
    @Autowired
    ExpiryScanner scanner;
    @Autowired
    NotificationTriggers triggers;
    @Autowired
    LinkRepository links;
    @Autowired
    VisitRepository visits;
    @Autowired
    LinkNotificationSettingsRepository settingsRepo;
    @Autowired
    NotificationDeliveryRepository deliveries;
    @MockitoSpyBean
    FiredTriggerRepository fired;
    @PersistenceContext
    EntityManager em;
    @Autowired
    TriggerLocks locks;

    User admin;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);
        NotificationChannel hook = data.channel(admin, "hook", ChannelType.WEBHOOK, "https://example.com/h");
        Link l = new Link(domain, "old", "https://example.com/t", admin);
        l.setExpiresAt(NOW.minusSeconds(5));
        link = data.save(l);
        LinkNotificationSettings s = new LinkNotificationSettings(links.getReferenceById(link.getId()));
        s.setOnExpired(true);
        s.setOnEachVisit(true);
        s.getChannels().add(hook);
        settingsRepo.saveAndFlush(s);
    }

    Visit visit(VisitOutcome outcome, DenyReason reason) {
        return visits.saveAndFlush(new Visit(links.getReferenceById(link.getId()), admin, NOW, outcome, reason, "GET",
                false, "203.0.113.9", "ua", null, null, null, null, null, null));
    }

    List<NotificationDelivery> sent(TriggerType type) {
        return deliveries.findAll().stream().filter(d -> d.getEvent() == type).toList();
    }

    @Test
    void concurrentExpiredFiringsKeepOneDeliveryAndTheRestOfTheBatch() throws Exception {
        List<Visit> batch = List.of(visit(VisitOutcome.REDIRECTED, null), visit(VisitOutcome.DENIED, DenyReason.EXPIRED));
        CountDownLatch bothChecked = new CountDownLatch(2);
        CountDownLatch scannerDone = new CountDownLatch(1);
        // Chaque thread lit « pas encore déclenché » puis attend l'autre : sans sérialisation, les deux insèrent.
        doAnswer(invocation -> {
            // Le dépôt espionné est un proxy JDK : la requête réelle passe par l'EntityManager de la transaction.
            Object result = em.createQuery("select f from FiredTrigger f where f.link.id = :link and f.triggerType = :type",
                            FiredTrigger.class)
                    .setParameter("link", invocation.getArgument(0))
                    .setParameter("type", invocation.getArgument(1))
                    .getResultStream().findFirst();
            if (invocation.getArgument(1) == TriggerType.EXPIRED) {
                bothChecked.countDown();
                bothChecked.await(1, TimeUnit.SECONDS);
                if (Thread.currentThread().getName().equals("writer")) {
                    scannerDone.await(5, TimeUnit.SECONDS);
                }
            }
            return result;
        }).when(fired).findByLinkIdAndTriggerType(any(), any());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scan = new Thread(() -> {
            try {
                scanner.scanLink(link.getId(), NOW);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                scannerDone.countDown();
            }
        }, "scanner");
        Thread write = new Thread(() -> triggers.onVisits(batch), "writer");
        scan.start();
        write.start();
        scan.join(15_000);
        write.join(15_000);

        assertThat(failure.get()).isNull();
        assertThat(sent(TriggerType.EXPIRED)).hasSize(1);
        assertThat(sent(TriggerType.VISIT)).hasSize(1);
        assertThat(fired.findAll()).singleElement()
                .satisfies(f -> assertThat(f.getTriggerType()).isEqualTo(TriggerType.EXPIRED));
        // verrous rendus à la fin des transactions, entrées retirées
        assertThat(locks.size()).isZero();
    }
}
