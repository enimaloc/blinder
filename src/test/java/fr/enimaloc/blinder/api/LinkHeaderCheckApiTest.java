package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.check.LinkHeaderCheck;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckRepository;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckService;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LinkHeaderCheckApiTest {

    private static final String BASE = "/_/api/v1/links";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkHeaderCheckRepository checks;
    @Autowired
    LinkHeaderCheckService checkService;
    @Autowired
    JsonMapper json;

    User admin;
    String token;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
        domain = data.domain("go.example.com", admin);
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String create(String extra) throws Exception {
        MvcResult result = mvc.perform(jsonBody(post(BASE), "{\"domainId\":\"%s\",\"targetUrl\":\"https://example.org/a\"%s}"
                        .formatted(domain.getId(), extra)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private LinkHeaderCheck stored(String id) {
        return checks.findByLinkId(UUID.fromString(id)).orElse(null);
    }

    @Test
    void createWithoutHeaderReturnsNull() throws Exception {
        mvc.perform(jsonBody(post(BASE), "{\"domainId\":\"%s\",\"targetUrl\":\"https://example.org/a\"}"
                        .formatted(domain.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiredHeader").value((Object) null));
        assertThat(checks.findAll()).isEmpty();
        create(",\"requiredHeader\":null");
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void createStoresAndReturnsHeader() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a",
                         "requiredHeader":{"name":" X-Token ","value":" abc "}}
                        """.formatted(domain.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiredHeader.name").value("X-Token"))
                .andExpect(jsonPath("$.requiredHeader.value").value("abc"));
        LinkHeaderCheck check = checks.findAll().getFirst();
        assertThat(check.getHeaderName()).isEqualTo("X-Token");
        assertThat(check.getExpectedValue()).isEqualTo("abc");
    }

    @Test
    void getAndListExposeHeader() throws Exception {
        String id = create(",\"requiredHeader\":{\"name\":\"X-Token\",\"value\":\"abc\"}");
        create("");
        mvc.perform(auth(get(BASE + "/" + id)))
                .andExpect(jsonPath("$.requiredHeader.name").value("X-Token"));
        mvc.perform(auth(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].requiredHeader.name".formatted(id)).value("X-Token"))
                .andExpect(jsonPath("$.items[?(@.requiredHeader == null)]").isNotEmpty());
    }

    @Test
    void invalidFieldsAreReportedUnderRequiredHeaderKeys() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a","requiredHeader":{"name":"X-Token"}}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors['requiredHeader.value']").value("Indiquez aussi la valeur attendue"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a","requiredHeader":{"value":"abc"}}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.name']").value("Indiquez aussi l'en-tête attendu"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a","requiredHeader":{"name":"X Token","value":"abc"}}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.name']").value("Nom d'en-tête invalide"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a",
                         "requiredHeader":{"name":"X-Token","value":"a\\r\\nX-Evil: 1"}}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.value']").value("Valeur invalide"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a",
                         "requiredHeader":{"name":"%s","value":"%s"}}
                        """.formatted(domain.getId(), "a".repeat(101), "v".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.name']").exists());
        assertThat(links.count()).isZero();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void unknownFieldInsideRequiredHeaderIsRejected() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/a",
                         "requiredHeader":{"name":"X","value":"v","valeur":"v"}}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.valeur']").value("Champ inconnu"));
        assertThat(links.count()).isZero();
    }

    @Test
    void putReplacesAndAbsentOrNullRemoves() throws Exception {
        String id = create(",\"requiredHeader\":{\"name\":\"X-Token\",\"value\":\"abc\"}");
        String body = "{\"domainId\":\"%s\",\"targetUrl\":\"https://example.org/a\"%s}";
        mvc.perform(jsonBody(put(BASE + "/" + id), body.formatted(domain.getId(),
                        ",\"requiredHeader\":{\"name\":\"X-Other\",\"value\":\"def\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader.name").value("X-Other"));
        assertThat(stored(id).getExpectedValue()).isEqualTo("def");

        mvc.perform(jsonBody(put(BASE + "/" + id), body.formatted(domain.getId(), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader").value((Object) null));
        assertThat(stored(id)).isNull();

        String other = create(",\"requiredHeader\":{\"name\":\"X-Token\",\"value\":\"abc\"}");
        mvc.perform(jsonBody(put(BASE + "/" + other), body.formatted(domain.getId(), ",\"requiredHeader\":null")))
                .andExpect(status().isOk());
        assertThat(stored(other)).isNull();
    }

    @Test
    void patchAbsentKeepsNullRemovesObjectReplaces() throws Exception {
        String id = create(",\"requiredHeader\":{\"name\":\"X-Token\",\"value\":\"abc\"}");

        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader.name").value("X-Token"))
                .andExpect(jsonPath("$.requiredHeader.value").value("abc"));
        assertThat(stored(id).getExpectedValue()).isEqualTo("abc");

        mvc.perform(jsonBody(patch(BASE + "/" + id),
                        "{\"requiredHeader\":{\"name\":\"X-Other\",\"value\":\"def\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader.name").value("X-Other"));
        assertThat(stored(id).getHeaderName()).isEqualTo("X-Other");

        // Objet = remplacement, pas fusion : un seul champ est une erreur.
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"requiredHeader\":{\"name\":\"X-Third\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['requiredHeader.value']").exists());
        assertThat(stored(id).getHeaderName()).isEqualTo("X-Other");

        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"requiredHeader\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader").value((Object) null));
        assertThat(stored(id)).isNull();
    }

    @Test
    void readRepresentationCanBeSentBackAsIs() throws Exception {
        String id = create(",\"requiredHeader\":{\"name\":\"X-Token\",\"value\":\"abc\"}");
        String representation = mvc.perform(auth(get(BASE + "/" + id))).andReturn().getResponse()
                .getContentAsString();
        mvc.perform(jsonBody(put(BASE + "/" + id), representation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeader.value").value("abc"));
    }

    @Test
    void foreignLinkHeaderIsNeverExposed() throws Exception {
        User other = data.admin("other");
        Link foreign = data.link(data.domain("other.example.com", other), "autre", "https://example.com/o");
        checkService.apply(foreign, "X-Foreign", "v");
        create("");
        mvc.perform(auth(get(BASE))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("X-Foreign"))));
        mvc.perform(auth(get(BASE + "/" + foreign.getId()))).andExpect(status().isNotFound());
    }
}
