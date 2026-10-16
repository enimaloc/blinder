package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class RedirectOptionsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
    }

    Link link(String slug, String target) {
        return new Link(domain, slug, target, domain.getOwner());
    }

    @Test
    void temporaryRedirectStatus() throws Exception {
        Link link = link("t", "https://example.com/t");
        link.setRedirectStatus(307);
        data.save(link);
        mvc.perform(get("http://go.example.com/t"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", "https://example.com/t"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void permanentRedirectOnlyForBareLinks() throws Exception {
        Link bare = link("p", "https://example.com/p");
        bare.setRedirectStatus(308);
        data.save(bare);
        mvc.perform(get("http://go.example.com/p")).andExpect(status().isPermanentRedirect());

        Link limited = link("l", "https://example.com/l");
        limited.setRedirectStatus(301);
        limited.setMaxUses(10);
        data.save(limited);
        mvc.perform(get("http://go.example.com/l"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/l"));
    }

    @Test
    void forwardsQueryWithTargetWinning() throws Exception {
        Link link = link("q", "https://example.com/q?ref=keep");
        link.setForwardQuery(true);
        data.save(link);
        mvc.perform(get("http://go.example.com/q").param("ref", "x").param("utm_source", "news"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/q?ref=keep&utm_source=news"));
    }

    @Test
    void passwordIsNeverForwarded() throws Exception {
        Link link = link("pw", "https://example.com/pw");
        link.setForwardQuery(true);
        link.setPasswordHash(encoder.encode("open-sesame"));
        data.save(link);
        mvc.perform(get("http://go.example.com/pw").param("pw", "open-sesame").param("utm", "1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/pw?utm=1"));
    }

    @Test
    void deniedRedirectsStayFound() throws Exception {
        Link link = link("d", "https://example.com/d");
        link.setRedirectStatus(307);
        link.setEnabled(false);
        link.setDeniedUrl("https://example.com/denied");
        data.save(link);
        mvc.perform(get("http://go.example.com/d"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/denied"));
    }
}
