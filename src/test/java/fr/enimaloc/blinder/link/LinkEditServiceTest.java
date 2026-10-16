package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.FiredTrigger;
import fr.enimaloc.blinder.notification.FiredTriggerRepository;
import fr.enimaloc.blinder.notification.LinkNotificationService;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.TriggerType;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestData.class)
class LinkEditServiceTest {

    @Autowired
    TestData data;
    @Autowired
    LinkEditService edits;
    @Autowired
    LinkRepository linkRepo;
    @Autowired
    FiredTriggerRepository fired;
    @MockitoSpyBean
    LinkNotificationService notifications;

    User admin;
    Domain domain;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        link = data.link(domain, "abc", "https://example.com/a");
        for (TriggerType type : TriggerType.values()) {
            if (type == TriggerType.EXHAUSTED || type == TriggerType.EXPIRED || type == TriggerType.THRESHOLD) {
                fired.saveAndFlush(new FiredTrigger(link, type));
            }
        }
    }

    private LinkForm form() {
        LinkForm form = new LinkForm();
        form.setDomainId(domain.getId());
        form.setSlug("abc");
        form.setTargetUrl("https://example.com/a");
        return form;
    }

    private Set<TriggerType> remaining() {
        return fired.findAll().stream().map(FiredTrigger::getTriggerType)
                .collect(Collectors.toSet());
    }

    private static final Set<TriggerType> ALL = Set.of(TriggerType.EXHAUSTED, TriggerType.EXPIRED, TriggerType.THRESHOLD);

    @Test
    void unrelatedChangeRearmsNothing() {
        LinkForm form = form();
        form.setTargetUrl("https://example.com/other");
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrderElementsOf(ALL);
    }

    @Test
    void maxUsesChangeRearmsExhaustedOnly() {
        LinkForm form = form();
        form.setMaxUses(5);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXPIRED, TriggerType.THRESHOLD);
    }

    @Test
    void expiresAtChangeRearmsExpiredOnly() {
        LinkForm form = form();
        form.setExpiresAt(LocalDateTime.now().plusDays(3));
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.THRESHOLD);
    }

    @Test
    void thresholdChangeRearmsThresholdOnly() {
        LinkForm form = form();
        form.setNotifyThreshold(10);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.EXPIRED);
    }

    @Test
    void switchingExhaustedOnRearmsIt() {
        LinkForm form = form();
        form.setNotifyExhausted(true);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXPIRED, TriggerType.THRESHOLD);

        // déjà activée : une nouvelle sauvegarde identique ne réarme rien
        fired.saveAndFlush(new FiredTrigger(linkRepo.findById(link.getId()).orElseThrow(), TriggerType.EXHAUSTED));
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrderElementsOf(ALL);
    }

    @Test
    void notBeforeChangeRearmsExpiredOnly() {
        LinkForm form = form();
        form.setNotBefore(LocalDateTime.now().minusDays(1));
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.THRESHOLD);
    }

    @Test
    void ttlChangeRearmsExpiredOnly() {
        LinkForm form = form();
        form.setTtlMinutes(60L);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.THRESHOLD);
    }

    @Test
    void switchingExpiredOnRearmsIt() {
        LinkForm form = form();
        form.setNotifyExpired(true);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.THRESHOLD);

        // déjà activée : une nouvelle sauvegarde identique ne réarme rien
        fired.saveAndFlush(new FiredTrigger(linkRepo.findById(link.getId()).orElseThrow(), TriggerType.EXPIRED));
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrderElementsOf(ALL);
    }

    @Test
    void clearingTheThresholdRearmsIt() {
        LinkForm form = form();
        form.setNotifyThreshold(10);
        edits.update(admin, link.getId(), form);
        fired.saveAndFlush(new FiredTrigger(linkRepo.findById(link.getId()).orElseThrow(), TriggerType.THRESHOLD));
        assertThat(remaining()).containsExactlyInAnyOrderElementsOf(ALL);

        form.setNotifyThreshold(null);
        edits.update(admin, link.getId(), form);
        assertThat(remaining()).containsExactlyInAnyOrder(TriggerType.EXHAUSTED, TriggerType.EXPIRED);
    }

    @Test
    void resetRearmsAllThree() {
        edits.resetUsage(admin, link.getId());
        assertThat(remaining()).isEmpty();
    }

    @Test
    void createWithForeignChannelCreatesNoLink() {
        User other = data.admin("other");
        NotificationChannel foreign = data.channel(other, "x", ChannelType.WEBHOOK, "https://example.com/x");
        LinkForm form = form();
        form.setSlug("new");
        form.setNotifyChannelIds(Set.of(foreign.getId()));
        assertThatThrownBy(() -> edits.create(admin, form)).isInstanceOf(FieldErrorException.class);
        assertThat(linkRepo.findByDomainAndSlug(domain, "new")).isEmpty();
    }

    @Test
    void failingNotificationApplyRollsBackLinkUpdate() {
        doThrow(new IllegalStateException("boom")).when(notifications).apply(any(), any(), any());
        LinkForm form = form();
        form.setTargetUrl("https://example.com/changed");
        form.setMaxUses(3);
        UUID id = link.getId();
        assertThatThrownBy(() -> edits.update(admin, id, form)).isInstanceOf(IllegalStateException.class);
        Link reloaded = linkRepo.findById(id).orElseThrow();
        assertThat(reloaded.getTargetUrl()).isEqualTo("https://example.com/a");
        assertThat(reloaded.getMaxUses()).isNull();
        assertThat(remaining()).containsExactlyInAnyOrderElementsOf(ALL);
    }

    @Test
    void failingNotificationApplyRollsBackLinkCreation() {
        doThrow(new IllegalStateException("boom")).when(notifications).apply(any(), any(), any());
        LinkForm form = form();
        form.setSlug("fresh");
        assertThatThrownBy(() -> edits.create(admin, form)).isInstanceOf(IllegalStateException.class);
        assertThat(linkRepo.findByDomainAndSlug(domain, "fresh")).isEmpty();
    }
}
