package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
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

import java.time.Instant;

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
class LinkAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;

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

    MockHttpServletRequestBuilder createLink(String slug, String target) {
        return post("/_/links").with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", target)
                .param("enabled", "true");
    }

    @Test
    void listShowsOwnLinksWithStatus() throws Exception {
        Link exhausted = data.link(domain, "done", "https://example.com");
        exhausted.setMaxUses(1);
        exhausted.setUseCount(1);
        data.save(exhausted);
        Domain theirs = data.domain("theirs.example.com", data.admin("other"));
        data.link(theirs, "secret", "https://example.com");

        mvc.perform(get("/_/").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("go.example.com/done")))
                .andExpect(content().string(containsString("Épuisé")))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void createsLinkWithNormalizedSlug() throws Exception {
        mvc.perform(createLink(" PROMO ", "https://example.com/x")
                        .param("maxUses", "3").param("ttlMinutes", "60"))
                .andExpect(redirectedUrl("/_/"));
        Link link = links.findByDomainAndSlug(domain, "promo").orElseThrow();
        assertThat(link.getMaxUses()).isEqualTo(3);
        assertThat(link.getTtlAfterFirstUse()).hasMinutes(60);
        assertThat(link.getOwner().getId()).isEqualTo(admin.getId());
    }

    @Test
    void blankSlugIsGenerated() throws Exception {
        mvc.perform(createLink("", "https://example.com")).andExpect(redirectedUrl("/_/"));
        assertThat(links.findAll()).singleElement()
                .satisfies(l -> assertThat(l.getSlug()).matches("[a-z0-9]{7}"));
    }

    @Test
    void rejectsDuplicateReservedAndInvalidSlugs() throws Exception {
        data.link(domain, "promo", "https://example.com");
        for (String slug : new String[]{"promo", "PROMO", "error", "_admin"}) {
            mvc.perform(createLink(slug, "https://example.com"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("form", "slug"));
        }
    }

    @Test
    void rejectsDangerousUrls() throws Exception {
        mvc.perform(createLink("a", "javascript:alert(1)"))
                .andExpect(model().attributeHasFieldErrors("form", "targetUrl"));
        mvc.perform(createLink("b", "https://example.com").param("deniedUrl", "//evil.com"))
                .andExpect(model().attributeHasFieldErrors("form", "deniedUrl"));
    }

    @Test
    void rejectsExpiryBeforeActivation() throws Exception {
        mvc.perform(createLink("c", "https://example.com")
                        .param("notBefore", "2026-10-20T10:00")
                        .param("expiresAt", "2026-10-19T10:00"))
                .andExpect(model().attributeHasFieldErrors("form", "expiresAt"));
    }

    @Test
    void cannotUseAnotherOwnersDomainOrLink() throws Exception {
        Domain theirs = data.domain("theirs.example.com", data.admin("other"));
        Link theirLink = data.link(theirs, "x", "https://example.com");
        mvc.perform(post("/_/links").with(asAdmin).with(csrf())
                        .param("domainId", theirs.getId().toString())
                        .param("targetUrl", "https://example.com").param("enabled", "true"))
                .andExpect(model().attributeHasFieldErrors("form", "domainId"));
        mvc.perform(get("/_/links/{id}", theirLink.getId()).with(asAdmin)).andExpect(status().isNotFound());
        mvc.perform(post("/_/links/{id}/delete", theirLink.getId()).with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateDetectsStaleVersion() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com");
        long staleVersion = link.getVersion();
        link.setTargetUrl("https://example.com/changed-elsewhere");
        data.save(link);

        mvc.perform(post("/_/links/{id}", link.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "promo")
                        .param("targetUrl", "https://example.com/mine")
                        .param("enabled", "true")
                        .param("version", String.valueOf(staleVersion)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("staleError", true));
        assertThat(links.findById(link.getId()).orElseThrow().getTargetUrl())
                .isEqualTo("https://example.com/changed-elsewhere");
    }

    @Test
    void resetMakesExhaustedLinkUsableAgain() throws Exception {
        Link link = data.link(domain, "once", "https://example.com");
        link.setMaxUses(1);
        link.setUseCount(1);
        link.setFirstUsedAt(Instant.now());
        data.save(link);

        mvc.perform(post("/_/links/{id}/reset", link.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/links/" + link.getId()));
        Link reset = links.findById(link.getId()).orElseThrow();
        assertThat(reset.getUseCount()).isZero();
        assertThat(reset.getFirstUsedAt()).isNull();

        mvc.perform(get("http://go.example.com/once")).andExpect(status().isFound());
    }

    @Test
    void deletesLink() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com");
        mvc.perform(post("/_/links/{id}/delete", link.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/"));
        assertThat(links.findById(link.getId())).isEmpty();
    }

    @Test
    void rejectsHugeTtlWithoutServerError() throws Exception {
        mvc.perform(createLink("huge", "https://example.com").param("ttlMinutes", "99999999999"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "ttlMinutes"));
    }

    @Test
    void rejectsMissingTargetUrl() throws Exception {
        mvc.perform(post("/_/links").with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "targetUrl"));
    }
}
