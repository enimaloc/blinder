package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.check.LinkHeaderCheck;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckRepository;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckService;
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
class LinkHeaderCheckAdminTest {

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

    private MockHttpServletRequestBuilder createLink(String slug) {
        return post("/_/links").with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", "https://example.com/")
                .param("redirectStatus", "302")
                .param("metaMode", "NONE");
    }

    private MockHttpServletRequestBuilder updateLink(Link link) {
        return post("/_/links/" + link.getId()).with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", link.getSlug())
                .param("targetUrl", "https://example.com/")
                .param("redirectStatus", "302")
                .param("metaMode", "NONE")
                .param("version", String.valueOf(links.findById(link.getId()).orElseThrow().getVersion()));
    }

    @Test
    void createStoresTrimmedHeader() throws Exception {
        mvc.perform(createLink("promo").param("headerName", "  X-Token ").param("headerValue", " abc "))
                .andExpect(redirectedUrl("/_/"));
        Link link = links.findAll().getFirst();
        LinkHeaderCheck check = checks.findByLinkId(link.getId()).orElseThrow();
        assertThat(check.getHeaderName()).isEqualTo("X-Token");
        assertThat(check.getExpectedValue()).isEqualTo("abc");
    }

    @Test
    void createWithoutHeaderStoresNothing() throws Exception {
        mvc.perform(createLink("promo").param("headerName", "").param("headerValue", " "))
                .andExpect(redirectedUrl("/_/"));
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void onlyOneFieldFilledIsAnErrorOnTheEmptyOneAndCreatesNothing() throws Exception {
        mvc.perform(createLink("promo").param("headerName", "X-Token"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "headerValue"));
        mvc.perform(createLink("promo").param("headerValue", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "headerName"));
        assertThat(links.findAll()).isEmpty();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void invalidNameOrValueIsRejected() throws Exception {
        mvc.perform(createLink("promo").param("headerName", "X Token").param("headerValue", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "headerName"))
                .andExpect(content().string(containsString("Nom d&#39;en-tête invalide")));
        mvc.perform(createLink("promo").param("headerName", "X-Token").param("headerValue", "a\r\nb"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "headerValue"))
                .andExpect(content().string(containsString("Valeur invalide")));
        mvc.perform(createLink("promo").param("headerName", "a".repeat(101)).param("headerValue", "abc"))
                .andExpect(model().attributeHasFieldErrors("form", "headerName"));
        mvc.perform(createLink("promo").param("headerName", "X-Token").param("headerValue", "v".repeat(501)))
                .andExpect(model().attributeHasFieldErrors("form", "headerValue"));
        assertThat(links.findAll()).isEmpty();
    }

    @Test
    void editFormShowsStoredHeader() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com/");
        checkService.apply(link, "X-Token", "abc");
        mvc.perform(get("/_/links/" + link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    LinkForm form = (LinkForm) result.getModelAndView().getModel().get("form");
                    assertThat(form.getHeaderName()).isEqualTo("X-Token");
                    assertThat(form.getHeaderValue()).isEqualTo("abc");
                })
                .andExpect(content().string(containsString("Contrôle d'en-tête")))
                .andExpect(content().string(containsString("value=\"abc\"")));
    }

    @Test
    void updateReplacesThenClearsHeaderAndBumpsVersion() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com/");
        checkService.apply(link, "X-Token", "abc");
        long before = links.findById(link.getId()).orElseThrow().getVersion();

        mvc.perform(updateLink(link).param("headerName", "X-Other").param("headerValue", "def"))
                .andExpect(redirectedUrl("/_/"));
        LinkHeaderCheck replaced = checks.findByLinkId(link.getId()).orElseThrow();
        assertThat(replaced.getHeaderName()).isEqualTo("X-Other");
        assertThat(replaced.getExpectedValue()).isEqualTo("def");
        assertThat(links.findById(link.getId()).orElseThrow().getVersion()).isGreaterThan(before);

        mvc.perform(updateLink(link)).andExpect(redirectedUrl("/_/"));
        assertThat(checks.findByLinkId(link.getId())).isEmpty();
    }

    @Test
    void editRoundTripWithPrefilledValuesKeepsTheCheck() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com/");
        checkService.apply(link, "X-Token", "abc");
        LinkForm[] prefilled = new LinkForm[1];
        mvc.perform(get("/_/links/" + link.getId()).with(asAdmin))
                .andExpect(result -> prefilled[0] = (LinkForm) result.getModelAndView().getModel().get("form"));
        // Le formulaire pré-rempli, renvoyé tel quel (changement d'un autre champ seulement), garde le contrôle.
        mvc.perform(updateLink(link).param("metaTitle", "Titre")
                        .param("headerName", prefilled[0].getHeaderName())
                        .param("headerValue", prefilled[0].getHeaderValue()))
                .andExpect(redirectedUrl("/_/"));
        assertThat(links.findById(link.getId()).orElseThrow().getMetaTitle()).isEqualTo("Titre");
        assertThat(checks.findByLinkId(link.getId()).orElseThrow().getExpectedValue()).isEqualTo("abc");
    }

    @Test
    void invalidUpdateKeepsLinkAndHeaderUnchanged() throws Exception {
        Link link = data.link(domain, "promo", "https://example.com/");
        checkService.apply(link, "X-Token", "abc");
        mvc.perform(updateLink(link).param("slug", "changed").param("headerName", "X-Token"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "headerValue"));
        assertThat(links.findById(link.getId()).orElseThrow().getSlug()).isEqualTo("promo");
        assertThat(checks.findByLinkId(link.getId()).orElseThrow().getExpectedValue()).isEqualTo("abc");
    }

    @Test
    void listShowsHeaderNameButNotItsValue() throws Exception {
        Link guarded = data.link(domain, "garde", "https://example.com/g");
        data.link(domain, "libre", "https://example.com/l");
        checkService.apply(guarded, "X-Token", "valeur-secrete");
        mvc.perform(get("/_/").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Contrôle d'en-tête")))
                .andExpect(content().string(containsString("X-Token")))
                .andExpect(content().string(not(containsString("valeur-secrete"))));
    }

    @Test
    void foreignLinkHeaderIsNeverShown() throws Exception {
        User other = data.admin("other");
        Link foreign = data.link(data.domain("other.example.com", other), "autre", "https://example.com/o");
        checkService.apply(foreign, "X-Foreign", "v");
        mvc.perform(get("/_/").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("X-Foreign"))));
        mvc.perform(get("/_/links/" + foreign.getId()).with(asAdmin))
                .andExpect(status().isNotFound());
    }
}
