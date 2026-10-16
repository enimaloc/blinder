package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class InterstitialTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
    }

    Link interstitial(String slug, String target, int seconds) {
        Link link = new Link(domain, slug, target, domain.getOwner());
        link.setInterstitial(true);
        link.setInterstitialSeconds(seconds);
        return link;
    }

    int useCount(Link link) {
        return links.findById(link.getId()).orElseThrow().getUseCount();
    }

    @Test
    void getShowsDestinationWithoutCounting() throws Exception {
        Link link = data.save(interstitial("go", "https://dest.example.org/full/path", 0));
        mvc.perform(get("http://go.example.com/go"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("dest.example.org")))
                .andExpect(content().string(containsString("https://dest.example.org/full/path")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("countdown"))));
        assertThat(useCount(link)).isZero();
    }

    @Test
    void postContinuesAndCounts() throws Exception {
        Link link = data.save(interstitial("go", "https://dest.example.org/p", 0));
        mvc.perform(post("http://go.example.com/go").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://dest.example.org/p"));
        assertThat(useCount(link)).isEqualTo(1);
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        Link link = data.save(interstitial("go", "https://dest.example.org/p", 0));
        mvc.perform(post("http://go.example.com/go")).andExpect(status().isForbidden());
        assertThat(useCount(link)).isZero();
    }

    @Test
    void countdownIsRendered() throws Exception {
        data.save(interstitial("go", "https://dest.example.org/p", 5));
        mvc.perform(get("http://go.example.com/go"))
                .andExpect(content().string(containsString("id=\"countdown\"")))
                .andExpect(content().string(containsString("5")));
    }

    @Test
    void limitedLinkShowsDomainOnly() throws Exception {
        Link link = interstitial("lim", "https://dest.example.org/secret-path", 0);
        link.setMaxUses(3);
        data.save(link);
        mvc.perform(get("http://go.example.com/lim"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dest.example.org")))
                .andExpect(content().string(not(containsString("secret-path"))));
    }

    @Test
    void passwordLinksSkipTheInterstitial() throws Exception {
        Link link = interstitial("pw", "https://dest.example.org/p", 0);
        link.setPasswordHash(encoder.encode("open-sesame"));
        data.save(link);
        mvc.perform(get("http://go.example.com/pw").param("pw", "open-sesame"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://dest.example.org/p"));
    }

    @Test
    void botsNeverSeeTheInterstitial() throws Exception {
        data.save(interstitial("go", "https://dest.example.org/p", 0));
        mvc.perform(get("http://go.example.com/go").header("User-Agent", "Discordbot/2.0"))
                .andExpect(status().isFound());
    }

    @Test
    void queryIsKeptThroughTheInterstitial() throws Exception {
        Link link = interstitial("q", "https://dest.example.org/p", 0);
        link.setForwardQuery(true);
        data.save(link);
        mvc.perform(get("http://go.example.com/q?utm=1"))
                .andExpect(content().string(containsString("/q?utm=1")));
        mvc.perform(post("http://go.example.com/q?utm=1").with(csrf()))
                .andExpect(redirectedUrl("https://dest.example.org/p?utm=1"));
    }
}
