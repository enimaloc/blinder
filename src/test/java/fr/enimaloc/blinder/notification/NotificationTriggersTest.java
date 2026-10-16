package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.stats.VisitRepository;
import fr.enimaloc.blinder.stats.VisitWriter;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "blinder.notifications.denials.threshold=3",
        "blinder.notifications.denials.window=10m",
        "blinder.notifications.denials.cooldown=1h"})
@AutoConfigureMockMvc
@Import(TestData.class)
class NotificationTriggersTest {

    static final String TARGET = "https://example.com/very-secret-target";

    @Autowired
    TestData data;
    @Autowired
    NotificationTriggers triggers;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkService linkService;
    @Autowired
    VisitRepository visits;
    @Autowired
    VisitWriter writer;
    @Autowired
    MockMvc mvc;
    @Autowired
    LinkNotificationSettingsRepository settingsRepo;
    @Autowired
    NotificationDeliveryRepository deliveries;
    @Autowired
    JsonMapper json;
    @Autowired
    NotificationChannelRepository channelRepo;
    @Autowired
    TransactionTemplate transactions;
    @MockitoSpyBean
    DeliveryService deliveryService;

    User admin;
    Domain domain;
    Link link;
    NotificationChannel hook;
    NotificationChannel off;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        link = data.link(domain, "plain", TARGET);
        hook = data.channel(admin, "hook", ChannelType.WEBHOOK, "https://example.com/h");
        off = data.channel(admin, "off", ChannelType.WEBHOOK, "https://example.com/off");
        off.setEnabled(false);
        channelRepo.saveAndFlush(off);
    }

    void settings(Consumer<LinkNotificationSettings> config) {
        LinkNotificationSettings s = new LinkNotificationSettings(links.getReferenceById(link.getId()));
        config.accept(s);
        s.getChannels().add(hook);
        s.getChannels().add(off);
        settingsRepo.saveAndFlush(s);
    }

    Visit visit(VisitOutcome outcome, DenyReason reason, Instant at) {
        return visits.saveAndFlush(new Visit(links.getReferenceById(link.getId()), admin, at, outcome, reason, "GET",
                false, "203.0.113.9", "Mozilla/5.0 Chrome/120.0", "Chrome", "Windows", null, null, "FR", "Paris"));
    }

    Visit redirect() {
        transactions.executeWithoutResult(status -> links.consume(link.getId(), Instant.now()));
        return visit(VisitOutcome.REDIRECTED, null, Instant.now());
    }

    List<NotificationDelivery> sent(TriggerType type) {
        return deliveries.findAll().stream().filter(d -> d.getEvent() == type).toList();
    }

    @Test
    void everyVisitIsNotifiedOnEnabledChannelsOnly() {
        settings(s -> s.setOnEachVisit(true));
        triggers.onVisits(List.of(redirect(), redirect()));
        assertThat(sent(TriggerType.VISIT)).hasSize(2)
                .allSatisfy(d -> assertThat(d.getChannel().getId()).isEqualTo(hook.getId()))
                .allSatisfy(d -> assertThat(d.getLinkLabel()).isEqualTo("go.example.com/plain"))
                .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PENDING));
    }

    @Test
    void payloadCarriesTheVisitTimeNotTheProcessingTime() {
        settings(s -> s.setOnEachVisit(true));
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        triggers.onVisits(List.of(visit(VisitOutcome.REDIRECTED, null, at)));
        assertThat(json.readTree(sent(TriggerType.VISIT).getFirst().getPayload()).get("occurredAt").asString())
                .isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void linkWithoutSettingsIsIgnored() {
        triggers.onVisits(List.of(redirect()));
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void thresholdFiresOnlyOnce() {
        settings(s -> s.setVisitThreshold(2));
        triggers.onVisits(List.of(redirect(), redirect(), redirect()));
        assertThat(sent(TriggerType.THRESHOLD)).hasSize(1);
        assertThat(sent(TriggerType.THRESHOLD).getFirst().getPayload()).contains("2 visites");
    }

    @Test
    void exhaustionFiresOnceThenRearmsAfterReset() {
        Link limited = links.findById(link.getId()).orElseThrow();
        limited.setMaxUses(1);
        links.saveAndFlush(limited);
        settings(s -> s.setOnExhausted(true));

        triggers.onVisits(List.of(redirect()));
        assertThat(sent(TriggerType.EXHAUSTED)).hasSize(1);

        triggers.onVisits(List.of(visit(VisitOutcome.DENIED, DenyReason.EXHAUSTED, Instant.now())));
        assertThat(sent(TriggerType.EXHAUSTED)).hasSize(1);

        linkService.resetUsage(admin, link.getId());
        triggers.onVisits(List.of(redirect()));
        assertThat(sent(TriggerType.EXHAUSTED)).hasSize(2);
    }

    @Test
    void editingTheLinkRearmsTheOneShotTriggers() {
        settings(s -> s.setOnExpired(true));
        triggers.onVisits(List.of(visit(VisitOutcome.DENIED, DenyReason.EXPIRED, Instant.now())));
        assertThat(sent(TriggerType.EXPIRED)).hasSize(1);
        triggers.onVisits(List.of(visit(VisitOutcome.DENIED, DenyReason.EXPIRED, Instant.now())));
        assertThat(sent(TriggerType.EXPIRED)).hasSize(1);

        var form = linkService.toForm(linkService.get(admin, link.getId()));
        linkService.update(admin, link.getId(), form);
        triggers.onVisits(List.of(visit(VisitOutcome.DENIED, DenyReason.EXPIRED, Instant.now())));
        assertThat(sent(TriggerType.EXPIRED)).hasSize(2);
    }

    @Test
    void repeatedDenialsAlertThenStaySilentUntilCooldownEnds() {
        settings(s -> s.setOnDenials(true));
        Instant t = Instant.now();
        triggers.onVisits(List.of(rejected(t), rejected(t), rejected(t)));
        assertThat(sent(TriggerType.DENIALS)).hasSize(1);
        assertThat(sent(TriggerType.DENIALS).getFirst().getPayload()).contains("3 en 10 min");

        triggers.onVisits(List.of(rejected(t.plusSeconds(1)), rejected(t.plusSeconds(1)), rejected(t.plusSeconds(1))));
        assertThat(sent(TriggerType.DENIALS)).hasSize(1);

        Instant later = t.plus(Duration.ofMinutes(61));
        triggers.onVisits(List.of(rejected(later), rejected(later), rejected(later)));
        assertThat(sent(TriggerType.DENIALS)).hasSize(2);
    }

    @Test
    void forbiddenAndThrottledCountButOtherDenialsDoNot() {
        settings(s -> s.setOnDenials(true));
        Instant t = Instant.now();
        triggers.onVisits(List.of(
                visit(VisitOutcome.DENIED, DenyReason.NOT_YET, t), visit(VisitOutcome.DENIED, DenyReason.DISABLED, t),
                visit(VisitOutcome.DENIED, DenyReason.FORBIDDEN, t), visit(VisitOutcome.THROTTLED, null, t)));
        assertThat(sent(TriggerType.DENIALS)).isEmpty();
        triggers.onVisits(List.of(visit(VisitOutcome.PASSWORD_REJECTED, null, t)));
        assertThat(sent(TriggerType.DENIALS)).hasSize(1);
    }

    Visit rejected(Instant at) {
        return visit(VisitOutcome.PASSWORD_REJECTED, null, at);
    }

    @Test
    void payloadIsParseableAndLeaksNeitherTargetNorPassword() throws Exception {
        settings(s -> s.setOnEachVisit(true));
        triggers.onVisits(List.of(redirect()));
        String payload = sent(TriggerType.VISIT).getFirst().getPayload();
        JsonNode node = json.readTree(payload);
        assertThat(node.get("event").asString()).isEqualTo("VISIT");
        assertThat(node.get("link").get("url").asString()).isEqualTo("https://go.example.com/plain");
        assertThat(node.get("link").get("slug").asString()).isEqualTo("plain");
        assertThat(node.get("visit").get("outcome").asString()).isEqualTo("REDIRECTED");
        assertThat(node.get("visit").get("ip").asString()).isEqualTo("203.0.113.9");
        assertThat(node.get("text").asString()).isEqualTo("Visite de go.example.com/plain depuis 203.0.113.9 (FR)")
                .isEqualTo(node.get("content").asString());
        assertThat(payload).doesNotContain("targetUrl").doesNotContain("very-secret-target").doesNotContain("assword");
    }

    @Test
    void visitsGoThroughTheWriterToDeliveries() throws Exception {
        settings(s -> s.setOnEachVisit(true));
        mvc.perform(get("http://go.example.com/plain")).andExpect(status().is3xxRedirection());
        writer.flush();
        assertThat(sent(TriggerType.VISIT)).hasSize(1);
    }

    @Test
    void aLinkWhoseNotificationFailsDoesNotPreventOthers() {
        settings(s -> s.setOnEachVisit(true));
        Link second = data.link(domain, "second", TARGET);
        LinkNotificationSettings s2 = new LinkNotificationSettings(links.getReferenceById(second.getId()));
        s2.setOnEachVisit(true);
        s2.getChannels().add(hook);
        settingsRepo.saveAndFlush(s2);
        Visit first = visit(VisitOutcome.REDIRECTED, null, Instant.now());
        Visit other = visits.saveAndFlush(new Visit(links.getReferenceById(second.getId()), admin, Instant.now(),
                VisitOutcome.REDIRECTED, null, "GET", false, "203.0.113.9", "ua", null, null, null, null, null, null));
        UUID failing = link.getId();
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(deliveryService)
                .enqueue(org.mockito.ArgumentMatchers.argThat(l -> l != null && l.getId().equals(failing)),
                        org.mockito.ArgumentMatchers.eq(TriggerType.VISIT), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        triggers.onVisits(List.of(first, other));

        assertThat(sent(TriggerType.VISIT)).singleElement()
                .satisfies(d -> assertThat(d.getLinkLabel()).isEqualTo("go.example.com/second"));
    }

    @Test
    void unknownLinkDoesNotPreventOthersFromBeingProcessed() {
        settings(c -> c.setOnEachVisit(true));
        Visit ghost = new Visit(new Link(domain, "ghost", TARGET, admin), admin, Instant.now(), VisitOutcome.REDIRECTED,
                null, "GET", false, "1.1.1.1", "ua", null, null, null, null, null, null);
        triggers.onVisits(List.of(ghost, visit(VisitOutcome.REDIRECTED, null, Instant.now())));
        assertThat(sent(TriggerType.VISIT)).hasSize(1);
    }
}
