package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
@TestPropertySource(properties = "blinder.admin.time-zone=Europe/Paris")
class LinkAdminTimeZoneTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;

    @Test
    void datetimeLocalIsInterpretedInConfiguredZoneAndRenderedBack() throws Exception {
        data.reset();
        User admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);

        mvc.perform(post("/_/links").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "tz")
                        .param("targetUrl", "https://example.com")
                        .param("enabled", "true")
                        .param("expiresAt", "2026-10-16T14:30"))
                .andExpect(redirectedUrl("/_/"));

        Link link = links.findByDomainAndSlug(domain, "tz").orElseThrow();
        assertThat(link.getExpiresAt()).isEqualTo(Instant.parse("2026-10-16T12:30:00Z"));

        mvc.perform(get("/_/links/{id}", link.getId()).with(user(new BlinderUserDetails(admin))))
                .andExpect(content().string(containsString("2026-10-16T14:30")));
    }
}
