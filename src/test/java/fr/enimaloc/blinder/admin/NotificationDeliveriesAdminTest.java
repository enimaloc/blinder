package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.DeliveryStatus;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationDelivery;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.notification.TriggerType;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class NotificationDeliveriesAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    NotificationDeliveryRepository deliveries;

    User admin;
    User other;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        other = data.admin("other");
        asAdmin = user(new BlinderUserDetails(admin));
    }

    private NotificationDelivery delivery(User owner, String channelName, String label, DeliveryStatus status) {
        NotificationChannel channel = data.channel(owner, channelName, ChannelType.EMAIL, "a@example.com");
        NotificationDelivery d = new NotificationDelivery(owner, channel, null, label, TriggerType.EXPIRED,
                "{\"text\":\"t\"}", Instant.now());
        d.setStatus(status);
        d.setLastError(status == DeliveryStatus.FAILED ? "HTTP 500" : null);
        return deliveries.saveAndFlush(d);
    }

    @Test
    void listShowsOnlyOwnDeliveries() throws Exception {
        delivery(admin, "CanalMien", "mien.test/a", DeliveryStatus.FAILED);
        delivery(other, "CanalLeur", "leur.test/b", DeliveryStatus.FAILED);
        mvc.perform(get("/_/notifications").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mien.test/a")))
                .andExpect(content().string(containsString("HTTP 500")))
                .andExpect(content().string(containsString("Relancer")))
                .andExpect(content().string(not(containsString("leur.test/b"))));
    }

    @Test
    void retryOwnFailedDelivery() throws Exception {
        NotificationDelivery d = delivery(admin, "C", "mien.test/a", DeliveryStatus.FAILED);
        mvc.perform(post("/_/notifications/deliveries/" + d.getId() + "/retry").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/notifications"));
        assertThat(deliveries.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void retryOfOtherOwnersDeliveryIs404() throws Exception {
        NotificationDelivery d = delivery(other, "C", "leur.test/b", DeliveryStatus.FAILED);
        mvc.perform(post("/_/notifications/deliveries/" + d.getId() + "/retry").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(deliveries.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void testButtonCreatesTestDelivery() throws Exception {
        NotificationChannel channel = data.channel(admin, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(post("/_/notifications/channels/" + channel.getId() + "/test").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/notifications"))
                .andExpect(flash().attribute("info", "Test programmé"));
        assertThat(deliveries.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getEvent()).isEqualTo(TriggerType.TEST);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        });
    }

    @Test
    void testOnOtherOwnersChannelIs404() throws Exception {
        NotificationChannel theirs = data.channel(other, "C", ChannelType.EMAIL, "a@example.com");
        mvc.perform(post("/_/notifications/channels/" + theirs.getId() + "/test").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(deliveries.findAll()).isEmpty();
    }
}
