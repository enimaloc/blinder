package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestData.class)
class LinkNotificationServiceTest {

    @Autowired
    TestData data;
    @Autowired
    LinkNotificationService service;
    @Autowired
    NotificationChannelRepository channelRepo;

    User admin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
    }

    @Test
    void preselectIgnoresDisabledChannels() {
        NotificationChannel on = data.channel(admin, "on", ChannelType.WEBHOOK, "https://example.com/on");
        on.setPreselected(true);
        channelRepo.saveAndFlush(on);
        NotificationChannel off = data.channel(admin, "off", ChannelType.WEBHOOK, "https://example.com/off");
        off.setPreselected(true);
        off.setEnabled(false);
        channelRepo.saveAndFlush(off);

        LinkForm form = new LinkForm();
        service.preselect(admin, form);

        assertThat(form.getNotifyChannelIds()).containsExactly(on.getId());
    }
}
