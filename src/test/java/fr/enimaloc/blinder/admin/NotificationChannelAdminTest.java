package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationChannelRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class NotificationChannelAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    NotificationChannelRepository channels;

    User admin;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        asAdmin = user(new BlinderUserDetails(admin));
    }

    private MockHttpServletRequestBuilder create(String type, String target) {
        return post("/_/notifications/channels").with(asAdmin).with(csrf())
                .param("name", "Mon canal").param("type", type).param("target", target)
                .param("enabled", "true");
    }

    @Test
    void createsWebhookWithHexSecret() throws Exception {
        mvc.perform(create("WEBHOOK", "https://hooks.example.com/x"))
                .andExpect(redirectedUrl("/_/notifications"));
        NotificationChannel c = channels.findByOwnerOrderByNameAsc(admin).getFirst();
        assertThat(c.getSecret()).matches("[0-9a-f]{64}");
        assertThat(c.getOwner().getId()).isEqualTo(admin.getId());
    }

    @Test
    void emailChannelHasNoSecret() throws Exception {
        mvc.perform(create("EMAIL", "me@example.com")).andExpect(redirectedUrl("/_/notifications"));
        assertThat(channels.findByOwnerOrderByNameAsc(admin).getFirst().getSecret()).isNull();
    }

    @Test
    void rejectsNonHttpWebhookUrl() throws Exception {
        mvc.perform(create("WEBHOOK", "ftp://example.com/x"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "target"));
        assertThat(channels.findAll()).isEmpty();
    }

    @Test
    void rejectsWebhookUrlWithUserInfo() throws Exception {
        mvc.perform(create("WEBHOOK", "https://user:pw@example.com/x"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "target"));
        assertThat(channels.findAll()).isEmpty();
    }

    @Test
    void rejectsGroupEmailAddress() throws Exception {
        mvc.perform(create("EMAIL", "Équipe: a@example.com, b@example.com;"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "target"));
        assertThat(channels.findAll()).isEmpty();
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        mvc.perform(create("EMAIL", "pas-un-mail"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "target"));
    }

    @Test
    void listIsOwnerIsolated() throws Exception {
        data.channel(admin, "Mien", ChannelType.EMAIL, "a@example.com");
        data.channel(data.admin("other"), "Leur", ChannelType.EMAIL, "b@example.com");
        mvc.perform(get("/_/notifications").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mien")))
                .andExpect(content().string(not(containsString("Leur"))));
    }

    @Test
    void otherOwnersChannelIs404() throws Exception {
        NotificationChannel theirs = data.channel(data.admin("other"), "Leur", ChannelType.EMAIL, "b@example.com");
        mvc.perform(get("/_/notifications/channels/" + theirs.getId()).with(asAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/notifications/channels/" + theirs.getId()).with(asAdmin).with(csrf())
                        .param("name", "x").param("type", "EMAIL").param("target", "c@example.com"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/notifications/channels/" + theirs.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/notifications/channels/" + theirs.getId() + "/secret").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(channels.findById(theirs.getId())).isPresent();
    }

    @Test
    void deletesOwnChannel() throws Exception {
        NotificationChannel mine = data.channel(admin, "Mien", ChannelType.EMAIL, "a@example.com");
        mvc.perform(post("/_/notifications/channels/" + mine.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/notifications"));
        assertThat(channels.findById(mine.getId())).isEmpty();
    }

    @Test
    void regeneratingChangesSecret() throws Exception {
        mvc.perform(create("WEBHOOK", "https://hooks.example.com/x"));
        NotificationChannel c = channels.findByOwnerOrderByNameAsc(admin).getFirst();
        String before = c.getSecret();
        mvc.perform(post("/_/notifications/channels/" + c.getId() + "/secret").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/notifications/channels/" + c.getId()));
        String after = channels.findById(c.getId()).orElseThrow().getSecret();
        assertThat(after).matches("[0-9a-f]{64}").isNotEqualTo(before);
    }

    @Test
    void editPageShowsSecretToOwner() throws Exception {
        mvc.perform(create("WEBHOOK", "https://hooks.example.com/x"));
        NotificationChannel c = channels.findByOwnerOrderByNameAsc(admin).getFirst();
        mvc.perform(get("/_/notifications/channels/" + c.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(c.getSecret())));
    }

    @Test
    void warnsWhenSmtpNotConfigured() throws Exception {
        mvc.perform(get("/_/notifications/channels/new").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(model().attribute("smtpConfigured", false))
                .andExpect(content().string(containsString("SMTP non configuré")));
    }
}
