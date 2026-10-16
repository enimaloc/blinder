package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import org.hamcrest.Matchers;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class TagApiTest {

    static final String BASE = "/_/api/v1/tags";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    User admin;
    String token;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return auth(b).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listsOwnTagsWithLinkCounts() throws Exception {
        Link link = data.link(data.domain("go.example.com", admin), "abc", "https://example.com");
        Tag b = data.tag(admin, "beta");
        data.tag(admin, "alpha");
        data.tag(data.admin("other"), "foreign");
        data.tagLink(link, b);
        mvc.perform(auth(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].links").value(0))
                .andExpect(jsonPath("$[1].name").value("beta"))
                .andExpect(jsonPath("$[1].links").value(1))
                .andExpect(jsonPath("$[1].id").value(b.getId().toString()));
    }

    @Test
    void createsWithLocationAndColor() throws Exception {
        mvc.perform(json(post(BASE), "{\"name\":\"  Promo  \",\"color\":\"#AABBCC\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString(BASE + "/")))
                .andExpect(jsonPath("$.name").value("Promo"))
                .andExpect(jsonPath("$.color").value("#aabbcc"))
                .andExpect(jsonPath("$.links").value(0));
    }

    @Test
    void duplicateNameIgnoringCaseIsRejected() throws Exception {
        data.tag(admin, "Promo");
        mvc.perform(json(post(BASE), "{\"name\":\"promo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Ce tag existe déjà"));
    }

    @Test
    void invalidNameAndColorAreFieldErrors() throws Exception {
        mvc.perform(json(post(BASE), "{\"name\":\"a,b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Virgule interdite"));
        mvc.perform(json(post(BASE), "{\"name\":\"ok\",\"color\":\"red\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.color").value("Couleur invalide"));
        mvc.perform(json(post(BASE), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Nom requis"));
    }

    @Test
    void createRejectsNonTextualNameAndColor() throws Exception {
        mvc.perform(json(post(BASE), "{\"name\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Texte attendu"));
        mvc.perform(json(post(BASE), "{\"name\":\"ok\",\"color\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.color").value("Texte attendu"));
        mvc.perform(json(post(BASE), "[1]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").exists());
    }

    @Test
    void patchDistinguishesAbsentFromNullAndEmptyColor() throws Exception {
        Tag tag = data.tag(admin, "promo");
        String url = BASE + "/" + tag.getId();
        mvc.perform(json(patch(url), "{\"color\":\"#112233\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("promo"))
                .andExpect(jsonPath("$.color").value("#112233"));
        mvc.perform(json(patch(url), "{\"name\":\"Soldes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Soldes"))
                .andExpect(jsonPath("$.color").value("#112233"));
        mvc.perform(json(patch(url), "{\"color\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").doesNotExist());
        mvc.perform(json(patch(url), "{\"color\":\"#445566\"}")).andExpect(status().isOk());
        mvc.perform(json(patch(url), "{\"color\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").doesNotExist())
                .andExpect(jsonPath("$.name").value("Soldes"));
    }

    @Test
    void patchErrors() throws Exception {
        Tag tag = data.tag(admin, "promo");
        data.tag(admin, "other");
        String url = BASE + "/" + tag.getId();
        mvc.perform(json(patch(url), "{\"name\":\"OTHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Ce tag existe déjà"));
        mvc.perform(json(patch(url), "{\"color\":\"zzz\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.color").value("Couleur invalide"));
        mvc.perform(json(patch(url), "{\"name\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Nom requis"));
        mvc.perform(json(patch(url), "{\"name\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        mvc.perform(json(patch(url), "[1]")).andExpect(status().isBadRequest());
        mvc.perform(json(patch(url), "{bad")).andExpect(status().isBadRequest());
    }

    @Test
    void foreignOrUnknownIdIs404() throws Exception {
        Tag foreign = data.tag(data.admin("other"), "x");
        mvc.perform(json(patch(BASE + "/" + foreign.getId()), "{\"name\":\"y\"}")).andExpect(status().isNotFound());
        mvc.perform(auth(delete(BASE + "/" + foreign.getId()))).andExpect(status().isNotFound());
        mvc.perform(auth(delete(BASE + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void deleteKeepsLinks() throws Exception {
        Link link = data.link(data.domain("go.example.com", admin), "abc", "https://example.com");
        Tag tag = data.tag(admin, "promo");
        data.tagLink(link, tag);
        mvc.perform(auth(delete(BASE + "/" + tag.getId()))).andExpect(status().isNoContent());
        mvc.perform(auth(get(BASE))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(auth(get("/_/api/v1/links/" + link.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(0)));
    }
}
