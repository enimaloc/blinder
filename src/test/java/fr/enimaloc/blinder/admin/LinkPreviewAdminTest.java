package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.MetaMode;
import fr.enimaloc.blinder.link.preview.LinkPreviewRepository;
import fr.enimaloc.blinder.link.preview.PreviewData;
import fr.enimaloc.blinder.link.preview.PreviewFetcher;
import fr.enimaloc.blinder.link.preview.PreviewStatus;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkPreviewAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkPreviewRepository previews;
    @MockitoBean
    PreviewFetcher fetcher;

    User admin;
    Domain domain;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        asAdmin = user(new BlinderUserDetails(admin));
        when(fetcher.fetch(anyString())).thenReturn(Optional.of(new PreviewData("Cible", null, null)));
    }

    @Test
    void savingAPassthroughLinkFetchesItsPreview() throws Exception {
        mvc.perform(post("/_/links").with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString()).param("slug", "pt")
                        .param("targetUrl", "https://dest.example.org/").param("enabled", "true")
                        .param("metaMode", "PASSTHROUGH"))
                .andExpect(redirectedUrl("/_/"));
        Link saved = links.findByDomainAndSlug(domain, "pt").orElseThrow();
        assertThat(previews.findByLinkId(saved.getId())).get()
                .satisfies(p -> assertThat(p.getStatus()).isEqualTo(PreviewStatus.OK));
    }

    @Test
    void otherModesNeverFetch() throws Exception {
        mvc.perform(post("/_/links").with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString()).param("slug", "n")
                        .param("targetUrl", "https://dest.example.org/").param("enabled", "true"))
                .andExpect(redirectedUrl("/_/"));
        verify(fetcher, never()).fetch(anyString());
    }

    @Test
    void refreshButtonFetchesAgainAndShowsStatus() throws Exception {
        Link link = new Link(domain, "r", "https://dest.example.org/", admin);
        link.setMetaMode(MetaMode.PASSTHROUGH);
        Link saved = data.save(link);
        mvc.perform(post("/_/links/{id}/preview/refresh", saved.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/links/" + saved.getId()));
        mvc.perform(get("/_/links/{id}", saved.getId()).with(asAdmin))
                .andExpect(content().string(containsString("Cible")))
                .andExpect(content().string(containsString("Rafraîchir l'aperçu")));
    }

    @Test
    void refreshOnAnotherOwnersLinkIsNotFound() throws Exception {
        Domain theirs = data.domain("theirs.example.com", data.admin("other"));
        Link foreign = data.link(theirs, "x", "https://dest.example.org/");
        mvc.perform(post("/_/links/{id}/preview/refresh", foreign.getId()).with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
