package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.LinkEditService;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Violation d'intégrité sur un lien : 409 rattaché au slug seulement si le slug est effectivement pris. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkApiConflictTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @MockitoBean
    LinkEditService edits;

    private String token;
    private Domain domain;

    private MockHttpServletRequestBuilder create(String slug) {
        return post("/_/api/v1/links").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"" + domain.getId() + "\",\"slug\":\"" + slug
                        + "\",\"targetUrl\":\"https://e.org\"}");
    }

    @BeforeEach
    void setUp() {
        data.reset();
        User admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
        domain = data.domain("go.example.com", admin);
        when(edits.create(any(), any())).thenThrow(new DataIntegrityViolationException("uk_links_domain_slug"));
    }

    @Test
    void integrityViolationOnATakenSlugIs409OnSlug() throws Exception {
        data.link(domain, "x", "https://taken.example.org");
        mvc.perform(create("X"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.slug").value("Ce slug existe déjà sur ce domaine"));
    }

    @Test
    void integrityViolationWithAFreeSlugIsAGeneric409() throws Exception {
        mvc.perform(create("free"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Conflit avec une donnée existante"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }
}
