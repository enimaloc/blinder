package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.LinkNotificationSettings;
import fr.enimaloc.blinder.notification.LinkNotificationSettingsRepository;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkNotificationAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkNotificationSettingsRepository settings;
    @Autowired
    fr.enimaloc.blinder.notification.NotificationChannelRepository channelRepo;

    User admin;
    Domain domain;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        asAdmin = user(new BlinderUserDetails(admin));
    }

    private MockHttpServletRequestBuilder createLink(String slug) {
        return post("/_/links").with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", "https://example.com/")
                .param("redirectStatus", "302")
                .param("metaMode", "NONE");
    }

    @Test
    void newFormPreselectsChannelAndTriggers() throws Exception {
        NotificationChannel pre = data.channel(admin, "Pre", ChannelType.EMAIL, "a@example.com");
        pre.setPreselected(true);
        data.channel(admin, "Autre", ChannelType.EMAIL, "b@example.com");
        channelRepo.saveAndFlush(pre);
        mvc.perform(get("/_/links/new").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    LinkForm form = (LinkForm) result.getModelAndView().getModel().get("form");
                    assertThat(form.getNotifyChannelIds()).containsExactly(pre.getId());
                    assertThat(form.isNotifyExhausted()).isTrue();
                    assertThat(form.isNotifyExpired()).isTrue();
                    assertThat(form.isNotifyDenials()).isTrue();
                    assertThat(form.isNotifyEachVisit()).isFalse();
                });
    }

    @Test
    void createPersistsSettings() throws Exception {
        NotificationChannel c = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(createLink("promo").param("notifyEachVisit", "true")
                        .param("notifyThreshold", "5")
                        .param("notifyChannelIds", c.getId().toString()))
                .andExpect(redirectedUrl("/_/"));
        Link link = links.findAll().getFirst();
        LinkNotificationSettings s = settings.findWithChannelsByLinkId(link.getId()).orElseThrow();
        assertThat(s.isOnEachVisit()).isTrue();
        assertThat(s.getVisitThreshold()).isEqualTo(5);
        assertThat(s.getChannels()).extracting(NotificationChannel::getId).containsExactly(c.getId());
    }

    @Test
    void foreignChannelRerendersAndCreatesNoLink() throws Exception {
        NotificationChannel theirs = data.channel(data.admin("other"), "Leur", ChannelType.EMAIL, "b@example.com");
        mvc.perform(createLink("promo").param("notifyChannelIds", theirs.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "notifyChannelIds"));
        assertThat(links.findAll()).isEmpty();
        mvc.perform(createLink("promo2").param("notifyChannelIds", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertThat(links.findAll()).isEmpty();
    }

    @Test
    void foreignChannelOnUpdateIsFieldErrorAndLinkUnchanged() throws Exception {
        NotificationChannel mine = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(createLink("promo").param("notifyExhausted", "true")
                .param("notifyChannelIds", mine.getId().toString()));
        Link link = links.findAll().getFirst();
        NotificationChannel theirs = data.channel(data.admin("other"), "Leur", ChannelType.EMAIL, "b@example.com");
        mvc.perform(post("/_/links/" + link.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "changed")
                        .param("targetUrl", "https://example.com/changed")
                        .param("redirectStatus", "302")
                        .param("metaMode", "NONE")
                        .param("notifyExhausted", "true")
                        .param("notifyChannelIds", theirs.getId().toString())
                        .param("version", String.valueOf(link.getVersion())))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "notifyChannelIds"));
        Link after = links.findById(link.getId()).orElseThrow();
        assertThat(after.getSlug()).isEqualTo("promo");
        assertThat(after.getTargetUrl()).isEqualTo("https://example.com/");
        assertThat(settings.findWithChannelsByLinkId(link.getId()).orElseThrow().getChannels())
                .extracting(NotificationChannel::getId).containsExactly(mine.getId());
    }

    @Test
    void deletingAnAttachedChannelDetachesItAndKeepsTheLink() throws Exception {
        NotificationChannel c = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(createLink("promo").param("notifyExhausted", "true")
                .param("notifyChannelIds", c.getId().toString()));
        Link link = links.findAll().getFirst();
        mvc.perform(post("/_/notifications/channels/" + c.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/notifications"));
        assertThat(links.findById(link.getId())).isPresent();
        assertThat(channelRepo.findById(c.getId())).isEmpty();
        assertThat(settings.findWithChannelsByLinkId(link.getId()).orElseThrow().getChannels()).isEmpty();
    }

    @Test
    void uncheckingEverythingDeletesSettings() throws Exception {
        NotificationChannel c = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(createLink("promo").param("notifyExhausted", "true")
                .param("notifyChannelIds", c.getId().toString()));
        Link link = links.findAll().getFirst();
        assertThat(settings.findByLinkId(link.getId())).isPresent();
        mvc.perform(post("/_/links/" + link.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "promo")
                        .param("targetUrl", "https://example.com/")
                        .param("redirectStatus", "302")
                        .param("metaMode", "NONE")
                        .param("version", String.valueOf(link.getVersion())))
                .andExpect(redirectedUrl("/_/"));
        assertThat(settings.findByLinkId(link.getId())).isEmpty();
    }

    @Test
    void editFormLoadsSettings() throws Exception {
        NotificationChannel c = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(createLink("promo").param("notifyDenials", "true")
                .param("notifyChannelIds", c.getId().toString()));
        Link link = links.findAll().getFirst();
        mvc.perform(get("/_/links/" + link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    LinkForm form = (LinkForm) result.getModelAndView().getModel().get("form");
                    assertThat(form.isNotifyDenials()).isTrue();
                    assertThat(form.isNotifyExhausted()).isFalse();
                    assertThat(form.getNotifyChannelIds()).containsExactly(c.getId());
                });
    }

    @Test
    void zeroThresholdIsFieldError() throws Exception {
        mvc.perform(createLink("promo").param("notifyThreshold", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "notifyThreshold"));
        assertThat(links.findAll()).isEmpty();
    }
}
