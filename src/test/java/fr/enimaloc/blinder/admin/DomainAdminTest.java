package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
@TestPropertySource(properties = "blinder.admin.domain=admin.example.com")
class DomainAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    DomainRepository domains;

    User admin;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        asAdmin = user(new BlinderUserDetails(admin));
    }

    @Test
    void listsOwnDomainsOnly() throws Exception {
        data.domain("go.example.com", admin);
        data.domain("theirs.example.com", data.admin("other"));
        mvc.perform(get("/_/domains").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("go.example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("theirs.example.com"))));
    }

    @Test
    void createsNormalizedDomain() throws Exception {
        mvc.perform(post("/_/domains").with(asAdmin).with(csrf())
                        .param("host", "  Go.Example.COM. ")
                        .param("enabled", "true")
                        .param("notFoundUrl", ""))
                .andExpect(redirectedUrl("/_/domains"));
        Domain created = domains.findByHost("go.example.com").orElseThrow();
        assertThat(created.getNotFoundUrl()).isNull();
    }

    @Test
    void rejectsInvalidDuplicateOrAdminHost() throws Exception {
        data.domain("go.example.com", admin);
        for (String host : new String[]{"not a host", "go.example.com", "admin.example.com"}) {
            mvc.perform(post("/_/domains").with(asAdmin).with(csrf())
                            .param("host", host).param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("form", "host"));
        }
    }

    @Test
    void rejectsDangerousNotFoundUrl() throws Exception {
        mvc.perform(post("/_/domains").with(asAdmin).with(csrf())
                        .param("host", "go.example.com").param("enabled", "true")
                        .param("notFoundUrl", "javascript:alert(1)"))
                .andExpect(model().attributeHasFieldErrors("form", "notFoundUrl"));
    }

    @Test
    void rejectsOverlongNotFoundUrl() throws Exception {
        mvc.perform(post("/_/domains").with(asAdmin).with(csrf())
                        .param("host", "go.example.com").param("enabled", "true")
                        .param("notFoundUrl", "https://example.com/" + "a".repeat(2029)))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "notFoundUrl"))
                .andExpect(model().attributeErrorCount("form", 1));
    }

    @Test
    void updatesDomain() throws Exception {
        Domain domain = data.domain("go.example.com", admin);
        mvc.perform(post("/_/domains/{id}", domain.getId()).with(asAdmin).with(csrf())
                        .param("host", "go.example.com")
                        .param("_enabled", "on") // case décochée : seul le marqueur Spring est envoyé
                        .param("notFoundUrl", "https://example.com/404")
                        .param("version", String.valueOf(domain.getVersion())))
                .andExpect(redirectedUrl("/_/domains"));
        Domain updated = domains.findById(domain.getId()).orElseThrow();
        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.getNotFoundUrl()).isEqualTo("https://example.com/404");
    }

    @Test
    void otherOwnersDomainIs404() throws Exception {
        Domain theirs = data.domain("theirs.example.com", data.admin("other"));
        mvc.perform(get("/_/domains/{id}", theirs.getId()).with(asAdmin)).andExpect(status().isNotFound());
    }

    @Test
    void refusesToDeleteDomainWithLinks() throws Exception {
        Domain domain = data.domain("go.example.com", admin);
        data.link(domain, "promo", "https://example.com");
        mvc.perform(post("/_/domains/{id}/delete", domain.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/domains"))
                .andExpect(flash().attributeExists("error"));
        assertThat(domains.findById(domain.getId())).isPresent();

        Domain empty = data.domain("empty.example.com", admin);
        mvc.perform(post("/_/domains/{id}/delete", empty.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/domains"));
        assertThat(domains.findById(empty.getId())).isEmpty();
    }

    @Test
    void staleVersionIsRejectedAndDomainUnchanged() throws Exception {
        Domain domain = data.domain("go.example.com", admin);
        mvc.perform(post("/_/domains/{id}", domain.getId()).with(asAdmin).with(csrf())
                        .param("host", "go.example.com")
                        .param("enabled", "true")
                        .param("notFoundUrl", "https://example.com/404")
                        .param("version", String.valueOf(domain.getVersion() + 5)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("staleError", true));
        assertThat(domains.findById(domain.getId()).orElseThrow().getNotFoundUrl()).isNull();
    }

    @Test
    void otherOwnersDomainCannotBeUpdatedOrDeleted() throws Exception {
        Domain theirs = data.domain("theirs.example.com", data.admin("other"));
        mvc.perform(post("/_/domains/{id}", theirs.getId()).with(asAdmin).with(csrf())
                        .param("host", "hijack.example.com").param("enabled", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/domains/{id}/delete", theirs.getId()).with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(domains.findById(theirs.getId()).orElseThrow().getHost()).isEqualTo("theirs.example.com");
    }

    @Test
    void renamingToAnExistingHostIsRejected() throws Exception {
        data.domain("taken.example.com", admin);
        Domain domain = data.domain("go.example.com", admin);
        mvc.perform(post("/_/domains/{id}", domain.getId()).with(asAdmin).with(csrf())
                        .param("host", "taken.example.com").param("enabled", "true")
                        .param("version", String.valueOf(domain.getVersion())))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "host"));
        assertThat(domains.findById(domain.getId()).orElseThrow().getHost()).isEqualTo("go.example.com");
    }
}
