package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.LinkEditService;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Course sur le slug : la contrainte d'unicité lève une violation d'intégrité → 409 rattaché au slug. */
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

    @Test
    void integrityViolationOnLinkEndpointIs409OnSlug() throws Exception {
        data.reset();
        User admin = data.admin("admin");
        String token = data.apiToken(admin, "ci");
        Domain domain = data.domain("go.example.com", admin);
        when(edits.create(any(), any())).thenThrow(new DataIntegrityViolationException("uk_links_domain_slug"));
        mvc.perform(post("/_/api/v1/links").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domainId\":\"" + domain.getId() + "\",\"slug\":\"x\",\"targetUrl\":\"https://e.org\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.slug").value("Ce slug existe déjà sur ce domaine"));
    }
}
