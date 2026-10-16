package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.stream.IntStream;

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
class TagAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    TagRepository tags;
    @Autowired
    LinkRepository links;
    @Autowired
    TransactionTemplate transactions;

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

    private java.util.List<String> tagNames(Link link) {
        return transactions.execute(s -> links.findById(link.getId()).orElseThrow().getTags().stream()
                .map(Tag::getName).sorted().toList());
    }

    @Test
    void linkFormCreatesTagsDedupedAndRedisplaysThemOnEdit() throws Exception {
        mvc.perform(createLink("promo").param("tags", " promo , Été 2026 ,promo")).andExpect(redirectedUrl("/_/"));
        Link link = links.findAll().getFirst();
        assertThat(tagNames(link)).containsExactly("promo", "Été 2026");
        assertThat(tags.findByOwnerOrderByNameKeyAsc(admin)).hasSize(2);
        mvc.perform(get("/_/links/" + link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(((LinkForm) result.getModelAndView().getModel().get("form")).getTags())
                        .isEqualTo("promo, Été 2026"));
    }

    @Test
    void existingTagIsReusedCaseInsensitively() throws Exception {
        Tag existing = data.tag(admin, "Promo");
        mvc.perform(createLink("a").param("tags", "promo"));
        mvc.perform(createLink("b").param("tags", "PROMO"));
        assertThat(tags.findByOwnerOrderByNameKeyAsc(admin)).extracting(Tag::getId).containsExactly(existing.getId());
        assertThat(links.findAll()).allSatisfy(l -> assertThat(tagNames(l)).containsExactly("Promo"));
    }

    @Test
    void updateReplacesTags() throws Exception {
        mvc.perform(createLink("a").param("tags", "one, two"));
        Link link = links.findAll().getFirst();
        mvc.perform(post("/_/links/" + link.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString()).param("slug", "a")
                        .param("targetUrl", "https://example.com/").param("redirectStatus", "302")
                        .param("metaMode", "NONE").param("tags", "two, three")
                        .param("version", String.valueOf(link.getVersion())))
                .andExpect(redirectedUrl("/_/"));
        assertThat(tagNames(link)).containsExactly("three", "two");
    }

    @Test
    void tooManyTagsIsFieldErrorAndNoLinkCreated() throws Exception {
        String csv = String.join(",", IntStream.rangeClosed(1, 21).mapToObj(i -> "t" + i).toList());
        mvc.perform(createLink("many").param("tags", csv))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "tags"));
        assertThat(links.findAll()).isEmpty();
        assertThat(tags.findAll()).isEmpty();
    }

    @Test
    void listIsFilteredByTagAndCombinableWithDomain() throws Exception {
        Tag promo = data.tag(admin, "promo");
        Link tagged = data.link(domain, "tgd", "https://example.com");
        data.link(domain, "plain", "https://example.com");
        data.tagLink(tagged, promo);
        Domain other = data.domain("other.example.com", admin);
        Link taggedOther = data.link(other, "tgo", "https://example.com");
        data.tagLink(taggedOther, promo);

        mvc.perform(get("/_/").param("tag", promo.getId().toString()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("go.example.com/tgd")))
                .andExpect(content().string(containsString("other.example.com/tgo")))
                .andExpect(content().string(not(containsString("/plain"))))
                .andExpect(content().string(containsString("class=\"tag\"")));
        mvc.perform(get("/_/").param("tag", promo.getId().toString()).param("domain", domain.getId().toString())
                        .with(asAdmin))
                .andExpect(content().string(containsString("go.example.com/tgd")))
                .andExpect(content().string(not(containsString("other.example.com/tgo"))));
    }

    @Test
    void foreignTagFilterIs404() throws Exception {
        Tag theirs = data.tag(data.admin("other"), "promo");
        mvc.perform(get("/_/").param("tag", theirs.getId().toString()).with(asAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void linkFormNeverResolvesNamesAgainstAnotherOwner() throws Exception {
        Tag theirs = data.tag(data.admin("other"), "promo");
        mvc.perform(createLink("a").param("tags", "promo"));
        Link link = links.findAll().getFirst();
        java.util.List<java.util.UUID> ids = transactions.execute(s -> links.findById(link.getId()).orElseThrow()
                .getTags().stream().map(Tag::getId).toList());
        assertThat(ids).doesNotContain(theirs.getId()).hasSize(1);
    }

    @Test
    void tagsPageListsCountsAndCreates() throws Exception {
        Tag promo = data.tag(admin, "promo");
        data.tagLink(data.link(domain, "a", "https://example.com"), promo);
        mvc.perform(get("/_/tags").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("promo")))
                .andExpect(content().string(containsString("/_/?tag=" + promo.getId())));
        mvc.perform(post("/_/tags").with(asAdmin).with(csrf()).param("name", "  Nouveau  tag ")
                        .param("color", "#ABCDEF"))
                .andExpect(redirectedUrl("/_/tags"));
        Tag created = tags.findByOwnerAndNameKey(admin, "nouveau tag").orElseThrow();
        assertThat(created.getName()).isEqualTo("Nouveau tag");
        assertThat(created.getColor()).isEqualTo("#abcdef");
    }

    @Test
    void createWithNoColorStoresNull() throws Exception {
        mvc.perform(post("/_/tags").with(asAdmin).with(csrf()).param("name", "x").param("color", "#123456")
                        .param("noColor", "true"))
                .andExpect(redirectedUrl("/_/tags"));
        assertThat(tags.findByOwnerAndNameKey(admin, "x").orElseThrow().getColor()).isNull();
    }

    @Test
    void renameToExistingNameIsFieldError() throws Exception {
        data.tag(admin, "promo");
        Tag other = data.tag(admin, "other");
        mvc.perform(post("/_/tags/" + other.getId()).with(asAdmin).with(csrf()).param("name", "PROMO"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce tag existe déjà")));
        assertThat(tags.findById(other.getId()).orElseThrow().getName()).isEqualTo("other");
    }

    @Test
    void renameAndColorAndRemoveColor() throws Exception {
        Tag tag = data.tag(admin, "promo");
        mvc.perform(post("/_/tags/" + tag.getId()).with(asAdmin).with(csrf()).param("name", "Soldes")
                        .param("color", "#FF0000"))
                .andExpect(redirectedUrl("/_/tags"));
        Tag renamed = tags.findById(tag.getId()).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("Soldes");
        assertThat(renamed.getNameKey()).isEqualTo("soldes");
        assertThat(renamed.getColor()).isEqualTo("#ff0000");
        mvc.perform(post("/_/tags/" + tag.getId()).with(asAdmin).with(csrf()).param("name", "Soldes")
                        .param("color", "#FF0000").param("noColor", "true"))
                .andExpect(redirectedUrl("/_/tags"));
        assertThat(tags.findById(tag.getId()).orElseThrow().getColor()).isNull();
        mvc.perform(post("/_/tags/" + tag.getId()).with(asAdmin).with(csrf()).param("name", "Soldes")
                        .param("color", "red"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Couleur invalide")));
    }

    @Test
    void deleteKeepsTheLink() throws Exception {
        Tag promo = data.tag(admin, "promo");
        Link link = data.link(domain, "a", "https://example.com");
        data.tagLink(link, promo);
        mvc.perform(post("/_/tags/" + promo.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/tags"));
        assertThat(tags.findById(promo.getId())).isEmpty();
        assertThat(links.findById(link.getId())).isPresent();
        assertThat(tagNames(link)).isEmpty();
    }

    @Test
    void foreignTagIs404OnEveryAction() throws Exception {
        Tag theirs = data.tag(data.admin("other"), "promo");
        mvc.perform(post("/_/tags/" + theirs.getId()).with(asAdmin).with(csrf()).param("name", "x"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/tags/" + theirs.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(tags.findById(theirs.getId()).orElseThrow().getName()).isEqualTo("promo");
    }

    @Test
    void navHasTagsEntryAndAnonymousIsRedirected() throws Exception {
        mvc.perform(get("/_/tags").with(asAdmin)).andExpect(content().string(containsString("href=\"/_/tags\"")));
        mvc.perform(get("/_/tags")).andExpect(status().is3xxRedirection());
    }

    @Test
    void pillsCarryTheirColourAndAReadableTextColour() throws Exception {
        Tag light = data.tag(admin, "clair");
        light.setColor("#ffff00");
        tags.save(light);
        Tag dark = data.tag(admin, "sombre");
        dark.setColor("#112233");
        tags.save(dark);
        Link link = data.link(domain, "pil", "https://example.com");
        data.tagLink(link, light);
        data.tagLink(link, dark);
        mvc.perform(get("/_/").with(asAdmin))
                .andExpect(content().string(containsString("--tag-color: #ffff00; --tag-text: #000")))
                .andExpect(content().string(containsString("--tag-color: #112233; --tag-text: #fff")));
        mvc.perform(get("/_/tags").with(asAdmin))
                .andExpect(content().string(containsString("--tag-color: #ffff00; --tag-text: #000")));
    }

    @Test
    void pillFilterLinksOmitTheDomainParameterWhenNoDomainIsSelected() throws Exception {
        Tag promo = data.tag(admin, "promo");
        data.tagLink(data.link(domain, "pf", "https://example.com"), promo);
        mvc.perform(get("/_/").with(asAdmin))
                .andExpect(content().string(containsString("href=\"/_/?tag=" + promo.getId() + "\"")))
                .andExpect(content().string(not(containsString("domain="))));
        mvc.perform(get("/_/").param("domain", domain.getId().toString()).with(asAdmin))
                .andExpect(content().string(containsString("tag=" + promo.getId() + "&amp;domain=" + domain.getId())));
    }
}
