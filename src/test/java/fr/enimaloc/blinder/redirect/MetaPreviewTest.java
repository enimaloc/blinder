package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.MetaMode;
import fr.enimaloc.blinder.link.preview.PreviewStatus;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class MetaPreviewTest {

    static final String BOT = "Mozilla/5.0 (compatible; Discordbot/2.0)";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
    }

    Link custom(String slug) {
        Link link = new Link(domain, slug, "https://dest.example.org/secret-target", domain.getOwner());
        link.setMetaMode(MetaMode.CUSTOM);
        link.setMetaTitle("Mon titre");
        link.setMetaDescription("Ma description");
        link.setMetaImageUrl("https://cdn.example.net/card.png");
        return link;
    }

    @Test
    void botsGetCustomMetaWithoutTarget() throws Exception {
        data.save(custom("c"));
        mvc.perform(get("http://go.example.com/c").header("User-Agent", BOT))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Mon titre\"")))
                .andExpect(content().string(containsString("content=\"Ma description\"")))
                .andExpect(content().string(containsString("content=\"https://cdn.example.net/card.png\"")))
                .andExpect(content().string(containsString("summary_large_image")))
                .andExpect(content().string(containsString("http://go.example.com/c")))
                .andExpect(content().string(not(containsString("secret-target"))));
    }

    @Test
    void headGetsMetaToo() throws Exception {
        data.save(custom("c"));
        mvc.perform(head("http://go.example.com/c"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void humansAreRedirected() throws Exception {
        data.save(custom("c"));
        mvc.perform(get("http://go.example.com/c")).andExpect(status().isFound());
    }

    @Test
    void protectedLinksStillShowTheirPreviewToBots() throws Exception {
        Link password = custom("pw");
        password.setPasswordHash(encoder.encode("open-sesame"));
        data.save(password);
        mvc.perform(get("http://go.example.com/pw").header("User-Agent", BOT))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mon titre")))
                .andExpect(content().string(not(containsString("name=\"password\""))));

        Link devices = custom("dev");
        devices.setDeviceProtected(true);
        Link saved = data.save(devices);
        Device device = data.device(domain.getOwner(), "Laptop");
        data.allowDevice(saved, device);
        mvc.perform(get("http://go.example.com/dev").header("User-Agent", BOT))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mon titre")));
    }

    @Test
    void deadLinksKeepTheirStatus() throws Exception {
        Link expired = custom("old");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        data.save(expired);
        mvc.perform(get("http://go.example.com/old").header("User-Agent", BOT)).andExpect(status().isGone());

        Link disabled = custom("off");
        disabled.setEnabled(false);
        data.save(disabled);
        mvc.perform(get("http://go.example.com/off").header("User-Agent", BOT)).andExpect(status().isNotFound());
    }

    @Test
    void passthroughUsesTheLastSuccessfulPreview() throws Exception {
        Link link = new Link(domain, "pt", "https://dest.example.org/p", domain.getOwner());
        link.setMetaMode(MetaMode.PASSTHROUGH);
        Link saved = data.save(link);
        mvc.perform(get("http://go.example.com/pt").header("User-Agent", BOT)).andExpect(status().isFound());

        data.preview(saved, "Titre cible", null, null, PreviewStatus.OK);
        mvc.perform(get("http://go.example.com/pt").header("User-Agent", BOT))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Titre cible")))
                .andExpect(content().string(containsString("content=\"summary\"")));
    }

    @Test
    void failedPassthroughFallsBackToDefaultBehaviour() throws Exception {
        Link link = new Link(domain, "ptf", "https://dest.example.org/p", domain.getOwner());
        link.setMetaMode(MetaMode.PASSTHROUGH);
        Link saved = data.save(link);
        data.preview(saved, "Ignoré", null, null, PreviewStatus.FAILED);
        mvc.perform(get("http://go.example.com/ptf").header("User-Agent", BOT)).andExpect(status().isFound());
    }

    @Test
    void metaValuesAreEscaped() throws Exception {
        Link link = custom("x");
        link.setMetaTitle("\"><script>alert(1)</script>");
        data.save(link);
        mvc.perform(get("http://go.example.com/x").header("User-Agent", BOT))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&quot;&gt;&lt;script&gt;")));
    }
}
