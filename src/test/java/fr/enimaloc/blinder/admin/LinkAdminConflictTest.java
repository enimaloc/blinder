package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.LinkEditService;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Violation d'intégrité dans le panel : « slug pris » seulement s'il l'est, sinon un conflit générique. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkAdminConflictTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @MockitoBean
    LinkEditService edits;

    User admin;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        when(edits.create(any(), any())).thenThrow(new DataIntegrityViolationException("uk_links_domain_slug"));
        when(edits.update(any(), any(), any())).thenThrow(new DataIntegrityViolationException("uk_links_domain_slug"));
    }

    private MockHttpServletRequestBuilder submit(String url, String slug) {
        return post(url).with(user(new BlinderUserDetails(admin))).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", "https://example.com")
                .param("enabled", "true");
    }

    @Test
    void createWithATakenSlugShowsTheSlugError() throws Exception {
        data.link(domain, "promo", "https://taken.example.org");
        mvc.perform(submit("/_/links", "PROMO"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce slug existe déjà sur ce domaine")))
                .andExpect(content().string(not(containsString("Conflit d'enregistrement"))));
    }

    @Test
    void createWithAFreeSlugShowsTheGenericConflict() throws Exception {
        mvc.perform(submit("/_/links", "free"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conflit d'enregistrement, réessayez")))
                .andExpect(content().string(not(containsString("Ce slug existe déjà"))));
    }

    @Test
    void updateKeepingItsOwnSlugIsNotASlugConflict() throws Exception {
        var link = data.link(domain, "mine", "https://example.com");
        mvc.perform(submit("/_/links/" + link.getId(), "mine"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conflit d'enregistrement, réessayez")))
                .andExpect(content().string(not(containsString("Ce slug existe déjà"))));
    }

    @Test
    void updateToASlugTakenByAnotherLinkShowsTheSlugError() throws Exception {
        var link = data.link(domain, "mine", "https://example.com");
        data.link(domain, "other", "https://example.org");
        mvc.perform(submit("/_/links/" + link.getId(), "other"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce slug existe déjà sur ce domaine")));
    }
}
