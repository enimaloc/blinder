package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.MetaMode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkRedirectOptionsAdminTest {

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

    MockHttpServletRequestBuilder create(String slug) {
        return post("/_/links").with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", "https://example.com/" + slug)
                .param("enabled", "true");
    }

    Link link(String slug) {
        return links.findByDomainAndSlug(domain, slug).orElseThrow();
    }

    @Test
    void savesRedirectOptions() throws Exception {
        mvc.perform(create("opts").param("redirectStatus", "307").param("forwardQuery", "true")
                        .param("interstitial", "true").param("interstitialSeconds", "5"))
                .andExpect(redirectedUrl("/_/"));
        Link saved = link("opts");
        assertThat(saved.getRedirectStatus()).isEqualTo(307);
        assertThat(saved.isForwardQuery()).isTrue();
        assertThat(saved.isInterstitial()).isTrue();
        assertThat(saved.getInterstitialSeconds()).isEqualTo(5);
    }

    @Test
    void permanentStatusOnlyForBareLinks() throws Exception {
        mvc.perform(create("bare").param("redirectStatus", "301")).andExpect(redirectedUrl("/_/"));
        assertThat(link("bare").getRedirectStatus()).isEqualTo(301);

        mvc.perform(create("lim").param("redirectStatus", "308").param("maxUses", "3"))
                .andExpect(model().attributeHasFieldErrors("form", "redirectStatus"));
        mvc.perform(create("pw").param("redirectStatus", "301").param("newPassword", "open-sesame"))
                .andExpect(model().attributeHasFieldErrors("form", "redirectStatus"));
        mvc.perform(create("odd").param("redirectStatus", "303"))
                .andExpect(model().attributeHasFieldErrors("form", "redirectStatus"));
    }

    @Test
    void validatesInterstitialSeconds() throws Exception {
        mvc.perform(create("s").param("interstitial", "true").param("interstitialSeconds", "61"))
                .andExpect(model().attributeHasFieldErrors("form", "interstitialSeconds"));
    }

    @Test
    void customMetaNeedsTitleAndSafeImage() throws Exception {
        mvc.perform(create("m1").param("metaMode", "CUSTOM"))
                .andExpect(model().attributeHasFieldErrors("form", "metaTitle"));
        mvc.perform(create("m2").param("metaMode", "CUSTOM").param("metaTitle", "T")
                        .param("metaImageUrl", "javascript:alert(1)"))
                .andExpect(model().attributeHasFieldErrors("form", "metaImageUrl"));
        mvc.perform(create("m3").param("metaMode", "CUSTOM").param("metaTitle", "T")
                        .param("metaDescription", "D").param("metaImageUrl", "https://cdn.example.net/c.png"))
                .andExpect(redirectedUrl("/_/"));
        Link saved = link("m3");
        assertThat(saved.getMetaMode()).isEqualTo(MetaMode.CUSTOM);
        assertThat(saved.getMetaTitle()).isEqualTo("T");
        assertThat(saved.getMetaImageUrl()).isEqualTo("https://cdn.example.net/c.png");
    }

    @Test
    void editFormShowsOptionsAndPassthroughWarning() throws Exception {
        Link link = new Link(domain, "w", "https://example.com/w", admin);
        link.setMetaMode(MetaMode.PASSTHROUGH);
        link.setMaxUses(2);
        link.setRedirectStatus(307);
        Link saved = data.save(link);
        mvc.perform(get("/_/links/{id}", saved.getId()).with(asAdmin))
                .andExpect(content().string(containsString("value=\"307\" selected")))
                .andExpect(content().string(containsString("visible par toute personne")))
                .andExpect(content().string(containsString("Ce lien est limité ou protégé")));
    }

    @Test
    void emptyMetaModeOnProtectedLinkIsFieldErrorNotServerError() throws Exception {
        Link link = new Link(domain, "nm", "https://example.com/nm", admin);
        link.setMaxUses(2);
        Link saved = data.save(link);
        mvc.perform(post("/_/links/{id}", saved.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("targetUrl", "https://example.com/nm")
                        .param("metaMode", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "metaMode"));
    }

    @Test
    void passthroughOnProtectedLinkFlashesWarning() throws Exception {
        mvc.perform(create("fw").param("metaMode", "PASSTHROUGH").param("newPassword", "open-sesame"))
                .andExpect(redirectedUrl("/_/"))
                .andExpect(flash().attributeExists("warning"));
    }

    @Test
    void passthroughOnBareLinkDoesNotFlash() throws Exception {
        mvc.perform(create("fb").param("metaMode", "PASSTHROUGH"))
                .andExpect(redirectedUrl("/_/"))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void rerenderedCreateFormWarnsFromPostedValues() throws Exception {
        mvc.perform(create("fe").param("metaMode", "PASSTHROUGH").param("maxUses", "3")
                        .param("interstitialSeconds", "99"))
                .andExpect(content().string(containsString("Ce lien est limité ou protégé")));
    }
}
