package fr.enimaloc.blinder.persistence;

import fr.enimaloc.blinder.api.token.ApiToken;
import fr.enimaloc.blinder.api.token.ApiTokenRepository;
import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceCredentialRepository;
import fr.enimaloc.blinder.device.DeviceEnrollmentRepository;
import fr.enimaloc.blinder.device.DeviceRepository;
import fr.enimaloc.blinder.device.DeviceRow;
import fr.enimaloc.blinder.device.DeviceTokens;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.link.MetaMode;
import fr.enimaloc.blinder.link.preview.LinkPreviewRepository;
import fr.enimaloc.blinder.link.preview.PreviewStatus;
import fr.enimaloc.blinder.link.UsageCounter;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.DeliveryStatus;
import fr.enimaloc.blinder.notification.FiredTrigger;
import fr.enimaloc.blinder.notification.FiredTriggerRepository;
import fr.enimaloc.blinder.notification.LinkNotificationSettings;
import fr.enimaloc.blinder.notification.LinkNotificationSettingsRepository;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationChannelRepository;
import fr.enimaloc.blinder.notification.NotificationDelivery;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.notification.TriggerType;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.stats.VisitDailyStat;
import fr.enimaloc.blinder.stats.VisitDailyStatRepository;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.stats.VisitRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagRepository;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.UUID;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestData.class)
abstract class AbstractPersistenceTest {

    @Autowired
    TestData data;

    @Autowired
    LinkRepository links;

    @Autowired
    UsageCounter usageCounter;

    @Autowired
    DeviceRepository devices;
    @Autowired
    DeviceCredentialRepository credentials;
    @Autowired
    DeviceEnrollmentRepository enrollments;
    @Autowired
    LinkPreviewRepository previews;

    @Autowired
    VisitRepository visits;
    @Autowired
    VisitDailyStatRepository dailyStats;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    LinkNotificationSettingsRepository settingsRepository;
    @Autowired
    FiredTriggerRepository firedTriggers;
    @Autowired
    NotificationDeliveryRepository deliveries;

    @Autowired
    TagRepository tags;
    @Autowired
    ApiTokenRepository apiTokens;

    @Autowired
    fr.enimaloc.blinder.stats.StatsQuery statsQuery;
    @Autowired
    fr.enimaloc.blinder.tag.TagService tagService;
    @Autowired
    fr.enimaloc.blinder.user.UserRepository users;
    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LinkService linkService;
    @Autowired
    fr.enimaloc.blinder.link.LinkEditService linkEdits;
    @Autowired
    fr.enimaloc.blinder.notification.LinkNotificationService linkNotifications;
    @Autowired
    fr.enimaloc.blinder.device.DeviceService deviceService;

    User owner;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        domain = data.domain("go.example.com", owner);
    }

    @Test
    void roundTripsAllLinkFields() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Link link = new Link(domain, "promo", "https://example.com/x", owner);
        link.setEnabled(false);
        link.setNotBefore(base);
        link.setExpiresAt(base.plus(Duration.ofDays(2)));
        link.setTtlAfterFirstUse(Duration.ofHours(24));
        link.setFirstUsedAt(base.plusSeconds(5));
        link.setMaxUses(3);
        link.setUseCount(1);
        link.setDeniedUrl("https://example.com/denied");
        Link saved = data.save(link);

        Link found = links.findById(saved.getId()).orElseThrow();
        assertThat(found.getSlug()).isEqualTo("promo");
        assertThat(found.getTargetUrl()).isEqualTo("https://example.com/x");
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getNotBefore()).isEqualTo(base);
        assertThat(found.getExpiresAt()).isEqualTo(base.plus(Duration.ofDays(2)));
        assertThat(found.getTtlAfterFirstUse()).isEqualTo(Duration.ofHours(24));
        assertThat(found.getFirstUsedAt()).isEqualTo(base.plusSeconds(5));
        assertThat(found.getMaxUses()).isEqualTo(3);
        assertThat(found.getUseCount()).isEqualTo(1);
        assertThat(found.getDeniedUrl()).isEqualTo("https://example.com/denied");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getDomain().getId()).isEqualTo(domain.getId());
    }

    @Test
    void notificationOnlyEditBumpsLinkVersion() {
        Link link = data.link(domain, "notif", "https://example.com/n");
        long before = links.findById(link.getId()).orElseThrow().getVersion();
        fr.enimaloc.blinder.link.LinkForm form = new fr.enimaloc.blinder.link.LinkForm();
        form.setDomainId(domain.getId());
        form.setSlug("notif");
        form.setTargetUrl("https://example.com/n");
        form.setVersion(before);
        form.setNotifyExhausted(true);
        linkEdits.update(owner, link.getId(), form);
        assertThat(links.findById(link.getId()).orElseThrow().getVersion()).isGreaterThan(before);
    }

    @Test
    void rejectsDuplicateSlugOnSameDomain() {
        data.link(domain, "promo", "https://a.example");
        assertThatThrownBy(() -> links.saveAndFlush(new Link(domain, "promo", "https://b.example", owner)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameSlugOnDifferentDomains() {
        Domain other = data.domain("other.example.com", owner);
        data.link(domain, "promo", "https://a.example");
        data.link(other, "promo", "https://b.example");
        assertThat(links.findByDomainAndSlug(other, "promo")).isPresent();
    }

    @Test
    void scopesQueriesByOwner() {
        User intruder = data.admin("intruder");
        Link mine = data.link(domain, "promo", "https://a.example");
        assertThat(links.findAllByOwnerOrderByCreatedAtDesc(owner)).extracting(Link::getId).containsExactly(mine.getId());
        assertThat(links.findAllByOwnerOrderByCreatedAtDesc(intruder)).isEmpty();
        assertThat(links.findByIdAndOwner(mine.getId(), intruder)).isEmpty();
        assertThat(links.findByIdAndOwner(mine.getId(), owner).orElseThrow().getDomain().getHost())
                .isEqualTo("go.example.com");
    }

    @Test
    void consumeSetsFirstUsedAtOnlyOnce() {
        Link link = data.link(domain, "ttl", "https://example.com");
        Instant first = Instant.parse("2026-10-16T12:00:00Z");
        assertThat(usageCounter.consume(link, first)).isTrue();
        assertThat(usageCounter.consume(link, first.plusSeconds(60))).isTrue();

        Link found = links.findById(link.getId()).orElseThrow();
        assertThat(found.getUseCount()).isEqualTo(2);
        assertThat(found.getFirstUsedAt()).isEqualTo(first);
    }

    @Test
    void consumeRefusesBeyondMaxUses() {
        Link link = new Link(domain, "two", "https://example.com", owner);
        link.setMaxUses(2);
        data.save(link);
        Instant now = Instant.now();
        assertThat(usageCounter.consume(link, now)).isTrue();
        assertThat(usageCounter.consume(link, now)).isTrue();
        assertThat(usageCounter.consume(link, now)).isFalse();
        assertThat(links.findById(link.getId()).orElseThrow().getUseCount()).isEqualTo(2);
    }

    @Test
    void consumeIsAtomicUnderConcurrency() throws Exception {
        Link link = new Link(domain, "once", "https://example.com", owner);
        link.setMaxUses(1);
        data.save(link);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return usageCounter.consume(link, Instant.now());
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(links.findById(link.getId()).orElseThrow().getUseCount()).isEqualTo(1);
    }

    /** SQLite runs with a single pooled connection, so two transactions can never overlap there. */
    boolean supportsOverlappingTransactions() {
        return true;
    }

    @Test
    void detachedEntitySaveDoesNotOverwriteCountedUsage() {
        Link created = new Link(domain, "stale", "https://example.com/a", owner);
        created.setMaxUses(1);
        data.save(created);
        Link detached = links.findById(created.getId()).orElseThrow();
        assertThat(usageCounter.consume(created, Instant.now())).isTrue();

        detached.setTargetUrl("https://example.com/b");
        links.saveAndFlush(detached);

        Link found = links.findById(created.getId()).orElseThrow();
        assertThat(found.getTargetUrl()).isEqualTo("https://example.com/b");
        assertThat(found.getUseCount()).isEqualTo(1);
        assertThat(found.getFirstUsedAt()).isNotNull();
    }

    @Test
    void entityUpdateDoesNotOverwriteConcurrentlyCountedUsage() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(supportsOverlappingTransactions());
        Link created = new Link(domain, "race", "https://example.com/a", owner);
        created.setMaxUses(1);
        data.save(created);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Link loaded = links.findById(created.getId()).orElseThrow();
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                assertThat(other.submit(() -> usageCounter.consume(created, Instant.now()))
                        .get(30, TimeUnit.SECONDS)).isTrue();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                other.shutdown();
            }
            loaded.setTargetUrl("https://example.com/b");
            links.saveAndFlush(loaded);
        });

        Link found = links.findById(created.getId()).orElseThrow();
        assertThat(found.getTargetUrl()).isEqualTo("https://example.com/b");
        assertThat(found.getUseCount()).isEqualTo(1);
        assertThat(found.getFirstUsedAt()).isNotNull();
    }

    @Test
    void resetUsageZeroesCounterAndFirstUse() {
        Link link = new Link(domain, "reset", "https://example.com", owner);
        link.setMaxUses(1);
        data.save(link);
        usageCounter.consume(link, Instant.now());
        assertThat(links.findById(link.getId()).orElseThrow().getUseCount()).isEqualTo(1);

        linkService.resetUsage(owner, link.getId());

        Link found = links.findById(link.getId()).orElseThrow();
        assertThat(found.getUseCount()).isZero();
        assertThat(found.getFirstUsedAt()).isNull();
    }

    @Test
    void roundTripsProtectionFields() {
        Link link = new Link(domain, "prot", "https://example.com", owner);
        link.setPasswordHash("{bcrypt}hash");
        link.setIpAllowlist("10.0.0.0/8,2001:db8:0:0:0:0:0:0/32");
        data.save(link);

        Link found = links.findById(link.getId()).orElseThrow();
        assertThat(found.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(found.getIpAllowlist()).isEqualTo("10.0.0.0/8,2001:db8:0:0:0:0:0:0/32");
    }

    @Test
    void deletingDeviceCascadesToLinksAndCredentials() {
        Link link = data.link(domain, "dev", "https://example.com");
        Device device = data.device(owner, "Laptop");
        data.credential(device, domain);
        data.allowDevice(link, device);
        assertThat(links.isDeviceAllowed(link.getId(), device.getId())).isTrue();
        assertThat(links.findDeviceIds(link.getId())).containsExactly(device.getId());

        devices.deleteById(device.getId());

        assertThat(links.findDeviceIds(link.getId())).isEmpty();
        assertThat(links.isDeviceAllowed(link.getId(), device.getId())).isFalse();
        assertThat(credentials.countByDeviceId(device.getId())).isZero();
        assertThat(links.findById(link.getId())).get().extracting(Link::isDeviceProtected).isEqualTo(true);
    }

    @Test
    void deletingLinkKeepsDevice() {
        Link link = data.link(domain, "dev2", "https://example.com");
        Device device = data.device(owner, "Phone");
        data.allowDevice(link, device);

        links.deleteById(link.getId());

        assertThat(devices.findById(device.getId())).isPresent();
    }

    @Test
    void enrollmentIsConsumedAtomicallyOnce() {
        Instant now = Instant.now();
        String token = data.enrollment(owner, domain, "Tablet", now.plusSeconds(600));
        String hash = DeviceTokens.sha256(token);
        assertThat(enrollments.consume(hash, now)).isEqualTo(1);
        assertThat(enrollments.consume(hash, now)).isZero();
        assertThat(enrollments.findByTokenHash(hash).orElseThrow().getUsedAt()).isNotNull();
    }

    @Test
    void expiredEnrollmentCannotBeConsumed() {
        Instant now = Instant.now();
        String token = data.enrollment(owner, domain, "Old", now.minusSeconds(1));
        assertThat(enrollments.consume(DeviceTokens.sha256(token), now)).isZero();
    }

    @Test
    void syncTicketRotatesOnlyOnce() {
        Instant now = Instant.now();
        Device device = data.device(owner, "Laptop");
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        devices.setTicket(device.getId(), a, now.plusSeconds(120));

        assertThat(devices.rotateTicket(device.getId(), a, b, now.plusSeconds(120), now)).isEqualTo(1);
        assertThat(devices.rotateTicket(device.getId(), a, "c".repeat(64), now.plusSeconds(120), now)).isZero();
        assertThat(devices.clearTicket(device.getId(), b, now)).isEqualTo(1);
        assertThat(devices.clearTicket(device.getId(), b, now)).isZero();
        assertThat(devices.findById(device.getId()).orElseThrow().getSyncTicketHash()).isNull();
    }

    @Test
    void expiredSyncTicketCannotRotate() {
        Instant now = Instant.now();
        Device device = data.device(owner, "Laptop");
        devices.setTicket(device.getId(), "a".repeat(64), now.minusSeconds(1));
        assertThat(devices.rotateTicket(device.getId(), "a".repeat(64), "b".repeat(64), now.plusSeconds(120), now))
                .isZero();
    }

    @Test
    void credentialIsUniquePerDeviceAndDomain() {
        Device device = data.device(owner, "Laptop");
        data.credential(device, domain);
        assertThatThrownBy(() -> data.credential(device, domain))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void roundTripsRedirectAndMetaFields() {
        Link link = new Link(domain, "opts", "https://example.com", owner);
        link.setRedirectStatus(307);
        link.setForwardQuery(true);
        link.setInterstitial(true);
        link.setInterstitialSeconds(5);
        link.setMetaMode(MetaMode.CUSTOM);
        link.setMetaTitle("Titre");
        link.setMetaDescription("Description");
        link.setMetaImageUrl("https://example.com/i.png");
        data.save(link);

        Link found = links.findById(link.getId()).orElseThrow();
        assertThat(found.getRedirectStatus()).isEqualTo(307);
        assertThat(found.isForwardQuery()).isTrue();
        assertThat(found.isInterstitial()).isTrue();
        assertThat(found.getInterstitialSeconds()).isEqualTo(5);
        assertThat(found.getMetaMode()).isEqualTo(MetaMode.CUSTOM);
        assertThat(found.getMetaTitle()).isEqualTo("Titre");
        assertThat(found.getMetaDescription()).isEqualTo("Description");
        assertThat(found.getMetaImageUrl()).isEqualTo("https://example.com/i.png");
    }

    @Test
    void newLinksHaveRedirectDefaults() {
        Link found = links.findById(data.link(domain, "plain", "https://example.com").getId()).orElseThrow();
        assertThat(found.getRedirectStatus()).isEqualTo(302);
        assertThat(found.isForwardQuery()).isFalse();
        assertThat(found.isInterstitial()).isFalse();
        assertThat(found.getMetaMode()).isEqualTo(MetaMode.NONE);
    }

    @Test
    void previewIsUniquePerLinkAndCascadesOnDelete() {
        Link link = data.link(domain, "prev", "https://example.com");
        data.preview(link, "T", "D", "https://example.com/i.png", PreviewStatus.OK);
        assertThat(previews.findByLinkId(link.getId())).get()
                .satisfies(p -> assertThat(p.getTitle()).isEqualTo("T"));
        assertThatThrownBy(() -> data.preview(link, "T2", null, null, PreviewStatus.OK))
                .isInstanceOf(DataIntegrityViolationException.class);

        links.deleteById(link.getId());
        assertThat(previews.findByLinkId(link.getId())).isEmpty();
    }

    @Test
    void roundTripsVisitAndDailyStat() {
        Link link = data.link(domain, "vis", "https://example.com");
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Visit saved = visits.saveAndFlush(new Visit(links.getReferenceById(link.getId()), owner, at,
                VisitOutcome.DENIED, DenyReason.FORBIDDEN, "GET", true, "203.0.113.9", "UA/1.0",
                "Chrome", "Windows", "https://ref.example/page?x=1", "ref.example", "FR", "Paris"));

        Visit found = visits.findById(saved.getId()).orElseThrow();
        assertThat(found.getLink().getId()).isEqualTo(link.getId());
        assertThat(found.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(found.getOccurredAt()).isEqualTo(at);
        assertThat(found.getOutcome()).isEqualTo(VisitOutcome.DENIED);
        assertThat(found.getDenyReason()).isEqualTo(DenyReason.FORBIDDEN);
        assertThat(found.getHttpMethod()).isEqualTo("GET");
        assertThat(found.isBot()).isTrue();
        assertThat(found.getIp()).isEqualTo("203.0.113.9");
        assertThat(found.getUserAgent()).isEqualTo("UA/1.0");
        assertThat(found.getBrowser()).isEqualTo("Chrome");
        assertThat(found.getOs()).isEqualTo("Windows");
        assertThat(found.getReferer()).isEqualTo("https://ref.example/page?x=1");
        assertThat(found.getRefererHost()).isEqualTo("ref.example");
        assertThat(found.getCountry()).isEqualTo("FR");
        assertThat(found.getCity()).isEqualTo("Paris");

        LocalDate day = LocalDate.of(2026, 10, 16);
        VisitDailyStat stat = new VisitDailyStat(links.getReferenceById(link.getId()), owner, day, VisitOutcome.REDIRECTED);
        stat.increment(3);
        dailyStats.saveAndFlush(stat);
        VisitDailyStat foundStat = dailyStats.findByLinkIdAndDayAndOutcome(link.getId(), day, VisitOutcome.REDIRECTED)
                .orElseThrow();
        assertThat(foundStat.getVisits()).isEqualTo(3);
        assertThat(foundStat.getDay()).isEqualTo(day);
        assertThat(foundStat.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(dailyStats.findByLinkIdAndDayAndOutcome(link.getId(), day, VisitOutcome.DENIED)).isEmpty();
    }

    @Test
    void roundTripsChannelSettingsAndDelivery() {
        Link link = data.link(domain, "notif", "https://example.com");
        NotificationChannel channel = data.channel(owner, "Hook", ChannelType.WEBHOOK, "https://hooks.example/x");
        channel.setSecret("s3cret");
        channel.setAllowPrivateNetwork(true);
        channels.saveAndFlush(channel);

        LinkNotificationSettings settings = new LinkNotificationSettings(links.getReferenceById(link.getId()));
        settings.setOnExhausted(true);
        settings.setVisitThreshold(5);
        settings.setChannels(new java.util.HashSet<>(Set.of(channel)));
        settingsRepository.saveAndFlush(settings);

        FiredTrigger fired = new FiredTrigger(links.getReferenceById(link.getId()), TriggerType.EXHAUSTED);
        fired.setFiredAt(Instant.now());
        firedTriggers.saveAndFlush(fired);

        Instant next = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        deliveries.saveAndFlush(new NotificationDelivery(owner, channel, link.getId(), "go.example.com/notif",
                TriggerType.EXHAUSTED, "{\"event\":\"EXHAUSTED\"}", next));

        NotificationChannel foundChannel = channels.findByIdAndOwner(channel.getId(), owner).orElseThrow();
        assertThat(foundChannel.getSecret()).isEqualTo("s3cret");
        assertThat(foundChannel.isAllowPrivateNetwork()).isTrue();
        assertThat(foundChannel.isEnabled()).isTrue();
        assertThat(channels.findByOwnerOrderByNameAsc(owner)).hasSize(1);
        assertThat(channels.findByOwnerAndPreselectedTrue(owner)).isEmpty();

        LinkNotificationSettings found = settingsRepository.findWithChannelsByLinkId(link.getId()).orElseThrow();
        assertThat(found.isOnExhausted()).isTrue();
        assertThat(found.isOnEachVisit()).isFalse();
        assertThat(found.getVisitThreshold()).isEqualTo(5);
        assertThat(found.getChannels()).extracting(NotificationChannel::getId).containsExactly(channel.getId());
        assertThat(settingsRepository.findByLinkId(link.getId())).isPresent();

        assertThat(firedTriggers.findByLinkIdAndTriggerType(link.getId(), TriggerType.EXHAUSTED)).isPresent();

        NotificationDelivery delivery = deliveries.findTop100ByOwnerOrderByCreatedAtDesc(owner).getFirst();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getLinkId()).isEqualTo(link.getId());
        assertThat(delivery.getNextAttemptAt()).isEqualTo(next);
        assertThat(deliveries.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                DeliveryStatus.PENDING, next)).hasSize(1);
    }

    @Test
    void deletingALinkCascadesStatsAndSettings() {
        Link link = data.link(domain, "casc", "https://example.com");
        Link ref = links.getReferenceById(link.getId());
        NotificationChannel channel = data.channel(owner, "Hook", ChannelType.EMAIL, "me@example.com");
        data.visit(link, VisitOutcome.REDIRECTED, Instant.now(), "203.0.113.1");
        dailyStats.saveAndFlush(new VisitDailyStat(ref, owner, LocalDate.of(2026, 10, 16), VisitOutcome.REDIRECTED));
        LinkNotificationSettings settings = new LinkNotificationSettings(ref);
        settings.setChannels(new java.util.HashSet<>(Set.of(channel)));
        settingsRepository.saveAndFlush(settings);
        firedTriggers.saveAndFlush(new FiredTrigger(ref, TriggerType.EXPIRED));
        NotificationDelivery delivery = deliveries.saveAndFlush(new NotificationDelivery(owner, channel, link.getId(),
                "label", TriggerType.EXPIRED, "{}", Instant.now()));

        links.delete(links.findById(link.getId()).orElseThrow());
        links.flush();

        assertThat(visits.count()).isZero();
        assertThat(dailyStats.count()).isZero();
        assertThat(settingsRepository.findByLinkId(link.getId())).isEmpty();
        assertThat(firedTriggers.findByLinkIdAndTriggerType(link.getId(), TriggerType.EXPIRED)).isEmpty();
        assertThat(deliveries.findByIdAndOwner(delivery.getId(), owner)).isPresent();
        assertThat(channels.findByIdAndOwner(channel.getId(), owner)).isPresent();
    }

    @Test
    void deleteOlderThanPurgesOnlyOldVisits() {
        Link link = data.link(domain, "purge", "https://example.com");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        data.visit(link, VisitOutcome.REDIRECTED, now.minus(Duration.ofDays(100)), "203.0.113.1");
        Visit recent = data.visit(link, VisitOutcome.REDIRECTED, now.minus(Duration.ofDays(1)), "203.0.113.2");

        int deleted = visits.deleteOlderThan(now.minus(Duration.ofDays(90)));

        assertThat(deleted).isEqualTo(1);
        assertThat(visits.findAll()).extracting(Visit::getId).containsExactly(recent.getId());
    }

    @Test
    void statsQueriesRunOnThisDatabase() {
        Link link = data.link(domain, "stats", "https://example.com");
        Instant now = Instant.now().minusSeconds(60);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.DENIED, now, "203.0.113.1");
        var period = fr.enimaloc.blinder.stats.StatsPeriod.D7;

        assertThat(statsQuery.totals(owner, fr.enimaloc.blinder.stats.StatsScope.all(), period).total()).isEqualTo(2);
        assertThat(statsQuery.totals(owner, fr.enimaloc.blinder.stats.StatsScope.link(link.getId()), period).uniqueIps()).isEqualTo(1);
        assertThat(statsQuery.topCountries(owner, fr.enimaloc.blinder.stats.StatsScope.all(), period)).hasSize(1);
        assertThat(statsQuery.topReferers(owner, fr.enimaloc.blinder.stats.StatsScope.link(link.getId()), period)).hasSize(1);
        assertThat(statsQuery.topLinks(owner, fr.enimaloc.blinder.stats.StatsScope.all(), period)).hasSize(1);
        assertThat(statsQuery.recent(owner, fr.enimaloc.blinder.stats.StatsScope.all(), period, 50)).hasSize(2);
        assertThat(statsQuery.daily(owner, fr.enimaloc.blinder.stats.StatsScope.link(link.getId()), period)).hasSize(7);
        StringWriter csv = new StringWriter();
        statsQuery.exportCsv(owner, fr.enimaloc.blinder.stats.StatsScope.all(), period, csv);
        assertThat(csv.toString().split("\r\n")).hasSize(3);
    }

    @Test
    void persistsTagWithColor() {
        Tag tag = new Tag(owner, "Promo Été");
        tag.setColor("#ff8800");
        tags.saveAndFlush(tag);

        Tag found = tags.findById(tag.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Promo Été");
        assertThat(found.getNameKey()).isEqualTo("promo été");
        assertThat(found.getColor()).isEqualTo("#ff8800");
        assertThat(tags.findByOwnerAndNameKey(owner, "promo été")).isPresent();
        assertThat(tags.findByIdAndOwner(tag.getId(), owner)).isPresent();
        assertThat(tags.findByOwnerOrderByNameKeyAsc(owner)).hasSize(1);
    }

    @Test
    void tagNameKeyIsUniquePerOwner() {
        User other = data.admin("other");
        data.tag(owner, "Promo");

        assertThatThrownBy(() -> tags.saveAndFlush(new Tag(owner, "PROMO")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tags.saveAndFlush(new Tag(other, "PROMO"))).isNotNull();
        assertThat(tags.findByOwnerAndNameKeyIn(owner, List.of("promo", "x"))).hasSize(1);
    }

    @Test
    void linkKeepsItsTags() {
        Link link = data.link(domain, "tagged", "https://example.com");
        Tag a = data.tag(owner, "a");
        Tag b = data.tag(owner, "b");
        data.tagLink(link, a);
        data.tagLink(link, b);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Link found = links.findById(link.getId()).orElseThrow();
            assertThat(found.getTags()).extracting(Tag::getName).containsExactlyInAnyOrder("a", "b");
        });
        var counts = tags.countLinksByTag(owner);
        assertThat(counts).hasSize(2);
        assertThat(((Number) counts.get(0)[1]).longValue()).isEqualTo(1L);
    }

    @Test
    void deletingLinkRemovesJoinRowsButKeepsTags() {
        Link link = data.link(domain, "tagged", "https://example.com");
        Tag a = data.tag(owner, "a");
        data.tagLink(link, a);

        links.deleteById(link.getId());
        links.flush();

        assertThat(tags.findById(a.getId())).isPresent();
        assertThat(tags.countLinksByTag(owner)).isEmpty();
    }

    @Test
    void deletingTagKeepsLinkWithoutIt() {
        Link link = data.link(domain, "tagged", "https://example.com");
        Tag a = data.tag(owner, "a");
        Tag b = data.tag(owner, "b");
        data.tagLink(link, a);
        data.tagLink(link, b);

        tags.deleteById(a.getId());
        tags.flush();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Link found = links.findById(link.getId()).orElseThrow();
            assertThat(found.getTags()).extracting(Tag::getName).containsExactly("b");
        });
    }

    private long count(String table, String column, UUID id) {
        return new TransactionTemplate(transactionManager).execute(status -> ((Number) entityManager
                .createNativeQuery("select count(*) from " + table + " where " + column + " = ?1")
                .setParameter(1, id.toString()).getSingleResult()).longValue());
    }

    @Test
    void deletingTagThroughServiceKeepsLinkAndRemovesJoinRow() {
        Link link = data.link(domain, "tagged", "https://example.com");
        Tag a = data.tag(owner, "a");
        data.tagLink(link, a);
        assertThat(count("link_tags", "tag_id", a.getId())).isEqualTo(1);

        tagService.delete(owner, a.getId());

        assertThat(tags.findById(a.getId())).isEmpty();
        assertThat(links.findById(link.getId())).isPresent();
        assertThat(count("link_tags", "tag_id", a.getId())).isZero();
        assertThat(count("link_tags", "link_id", link.getId())).isZero();
    }

    @Test
    void deletingUserCascadesToTagsJoinRowsAndTokens() {
        User other = data.admin("other");
        Link link = data.link(domain, "tagged", "https://example.com");
        Tag tag = data.tag(other, "t");
        data.tagLink(link, tag);
        data.apiToken(other, "ci");
        assertThat(count("link_tags", "tag_id", tag.getId())).isEqualTo(1);
        assertThat(count("api_tokens", "owner_id", other.getId())).isEqualTo(1);

        users.deleteById(other.getId());
        users.flush();

        assertThat(count("tags", "owner_id", other.getId())).isZero();
        assertThat(count("link_tags", "tag_id", tag.getId())).isZero();
        assertThat(count("api_tokens", "owner_id", other.getId())).isZero();
        assertThat(links.findById(link.getId())).isPresent();
    }

    @Test
    void findsApiTokenByHashWithOwner() {
        String raw = data.apiToken(owner, "ci");

        ApiToken found = apiTokens.findByTokenHash(DeviceTokens.sha256(raw)).orElseThrow();
        assertThat(found.getOwner().getUsername()).isEqualTo("admin");
        assertThat(found.getName()).isEqualTo("ci");
        assertThat(found.getTokenPrefix()).isEqualTo(raw.substring(0, 10));
        assertThat(found.getLastUsedAt()).isNull();
        assertThat(apiTokens.findByOwnerOrderByCreatedAtDesc(owner)).hasSize(1);
        assertThat(apiTokens.findByIdAndOwner(found.getId(), owner)).isPresent();
        assertThat(apiTokens.findByTokenHash("nope")).isEmpty();
    }

    @Test
    void touchUpdatesLastUsedOnlyOncePerWindow() {
        String raw = data.apiToken(owner, "ci");
        ApiToken token = apiTokens.findByTokenHash(DeviceTokens.sha256(raw)).orElseThrow();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertThat(apiTokens.touch(token.getId(), now, now.minusSeconds(60))).isEqualTo(1);
        assertThat(apiTokens.touch(token.getId(), now.plusSeconds(10), now.minusSeconds(50))).isZero();
        assertThat(apiTokens.touch(token.getId(), now.plusSeconds(120), now.plusSeconds(60))).isEqualTo(1);
        assertThat(apiTokens.findById(token.getId()).orElseThrow().getLastUsedAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void loadsDevicesAndNotificationSettingsOfSeveralLinksAtOnce() {
        Link first = data.link(domain, "un", "https://example.com/1");
        Link second = data.link(domain, "deux", "https://example.com/2");
        Link bare = data.link(domain, "trois", "https://example.com/3");
        Device phone = data.device(owner, "phone");
        Device laptop = data.device(owner, "laptop");
        data.allowDevice(first, phone);
        data.allowDevice(first, laptop);
        data.allowDevice(second, laptop);
        NotificationChannel hook = data.channel(owner, "hook", ChannelType.WEBHOOK, "https://hooks.example/a");
        LinkNotificationSettings settings = new LinkNotificationSettings(links.getReferenceById(second.getId()));
        settings.setOnExpired(true);
        settings.setChannels(new java.util.HashSet<>(Set.of(hook)));
        settingsRepository.saveAndFlush(settings);
        List<UUID> ids = List.of(first.getId(), second.getId(), bare.getId());

        var devicesByLink = linkService.deviceIds(ids);
        assertThat(devicesByLink.get(first.getId())).containsExactlyInAnyOrder(phone.getId(), laptop.getId());
        assertThat(devicesByLink.get(second.getId())).containsExactly(laptop.getId());
        assertThat(devicesByLink).doesNotContainKey(bare.getId());

        var settingsByLink = linkNotifications.settingsFor(ids);
        assertThat(settingsByLink).containsOnlyKeys(second.getId());
        assertThat(settingsByLink.get(second.getId()).isOnExpired()).isTrue();
        assertThat(settingsByLink.get(second.getId()).getChannels()).extracting(NotificationChannel::getId)
                .containsExactly(hook.getId());
    }

    @Test
    void countsDomainsOfEachDeviceInOneQuery() {
        Domain other = data.domain("other.example.com", owner);
        Device phone = data.device(owner, "phone");
        Device laptop = data.device(owner, "laptop");
        data.credential(phone, domain);
        data.credential(phone, other);
        User stranger = data.admin("stranger");
        data.credential(data.device(stranger, "foreign"), data.domain("foreign.example.com", stranger));

        assertThat(deviceService.list(owner)).extracting(r -> r.device().getName(), DeviceRow::domainCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("laptop", 0L),
                        org.assertj.core.groups.Tuple.tuple("phone", 2L));
    }
}
