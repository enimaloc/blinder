package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestData.class, RedirectControllerTest.FixedClock.class})
class RedirectControllerTest {

    static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    DomainRepository domains;

    User owner;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        domain = data.domain("go.example.com", owner);
    }

    int useCount(Link link) {
        return links.findById(link.getId()).orElseThrow().getUseCount();
    }

    @Test
    void redirectsActiveLinkWithNoStoreAndCountsUse() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com/target");
        mvc.perform(get("http://go.example.com/promo"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/target"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(useCount(link)).isEqualTo(1);
    }

    @Test
    void slugAndHostAreCaseInsensitive() throws Exception {
        data.link(domain, "promo", "https://example.com/target");
        mvc.perform(get("http://GO.Example.COM/PROMO"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/target"));
    }

    @Test
    void slugNamedProfileIsNotShadowedByDataRest() throws Exception {
        data.link(domain, "profile", "https://example.com/me");
        mvc.perform(get("http://go.example.com/profile"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/me"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("http://unknown.example.com/promo"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void disabledDomainIs404() throws Exception {
        data.link(domain, "promo", "https://example.com");
        domain.setEnabled(false);
        domains.save(domain);
        mvc.perform(get("http://go.example.com/promo")).andExpect(status().isNotFound());
    }

    @Test
    void unknownSlugUsesDomainNotFoundUrl() throws Exception {
        mvc.perform(get("http://go.example.com/nope")).andExpect(status().isNotFound());
        domain.setNotFoundUrl("https://example.com/404");
        domains.save(domain);
        mvc.perform(get("http://go.example.com/nope"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/404"));
    }

    @Test
    void disabledAndNotYetLookExactlyLikeUnknown() throws Exception {
        Link disabled = data.link(domain, "off", "https://example.com");
        disabled.setEnabled(false);
        data.save(disabled);
        Link later = data.link(domain, "later", "https://example.com");
        later.setNotBefore(NOW.plusSeconds(60));
        data.save(later);

        String unknownBody = body("http://go.example.com/nope", 404);
        assertThat(body("http://go.example.com/off", 404)).isEqualTo(unknownBody);
        assertThat(body("http://go.example.com/later", 404)).isEqualTo(unknownBody);
    }

    @Test
    void expiredAndExhaustedAre410() throws Exception {
        Link expired = data.link(domain, "old", "https://example.com");
        expired.setExpiresAt(NOW);
        data.save(expired);
        mvc.perform(get("http://go.example.com/old")).andExpect(status().isGone());

        Link once = data.link(domain, "once", "https://example.com");
        once.setMaxUses(1);
        data.save(once);
        mvc.perform(get("http://go.example.com/once")).andExpect(status().isFound());
        mvc.perform(get("http://go.example.com/once")).andExpect(status().isGone());
        assertThat(useCount(once)).isEqualTo(1);
    }

    @Test
    void ttlAfterFirstUseExpires() throws Exception {
        Link link = data.link(domain, "ttl", "https://example.com");
        link.setTtlAfterFirstUse(Duration.ofMinutes(10));
        link.setFirstUsedAt(NOW.minus(Duration.ofMinutes(10)));
        data.save(link);
        mvc.perform(get("http://go.example.com/ttl")).andExpect(status().isGone());
    }

    @Test
    void deniedUrlTakesPrecedence() throws Exception {
        Link link = data.link(domain, "off", "https://example.com");
        link.setEnabled(false);
        link.setDeniedUrl("https://example.com/denied");
        data.save(link);
        mvc.perform(get("http://go.example.com/off"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/denied"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void headAndBotsDoNotConsumeUsesNorSeeTargetOfLimitedLink() throws Exception {
        Link link = data.link(domain, "once", "https://example.com/secret-target");
        link.setMaxUses(1);
        data.save(link);

        mvc.perform(head("http://go.example.com/once"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("http://go.example.com/once")
                        .header("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0)"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-target"))));
        assertThat(useCount(link)).isZero();

        mvc.perform(get("http://go.example.com/once"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/secret-target"));
        assertThat(useCount(link)).isEqualTo(1);
    }

    @Test
    void botsDoNotSeeTargetOfTtlLink() throws Exception {
        Link link = data.link(domain, "ttl", "https://example.com/secret-target");
        link.setTtlAfterFirstUse(Duration.ofHours(1));
        data.save(link);
        mvc.perform(get("http://go.example.com/ttl")
                        .header("User-Agent", "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
        assertThat(links.findById(link.getId()).orElseThrow().getFirstUsedAt()).isNull();
    }

    @Test
    void nonCountableVisitsOfUnlimitedLinkStillRedirect() throws Exception {
        Link link = data.link(domain, "open", "https://example.com/open");
        mvc.perform(head("http://go.example.com/open"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/open"));
        mvc.perform(get("http://go.example.com/open")
                        .header("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0)"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/open"));
        assertThat(useCount(link)).isZero();
    }

    @Test
    void doesNotCreateSession() throws Exception {
        data.link(domain, "promo", "https://example.com");
        MvcResult result = mvc.perform(get("http://go.example.com/promo")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    String body(String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }
}
