package fr.enimaloc.blinder;

import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.setup.SetupGuard;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LotOneEndToEndTest {

    static final String PASSWORD = "an-admin-passphrase";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    SetupGuard guard;
    @Autowired
    DomainRepository domains;

    @Test
    void setupLoginCreateRedirectExhaust() throws Exception {
        data.reset();
        guard.refresh();
        String token = guard.currentToken().orElseThrow();

        mvc.perform(post("http://go.example.com/_/setup").with(csrf())
                        .param("token", token)
                        .param("username", "admin")
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("host", "go.example.com"))
                .andExpect(redirectedUrl("/_/login?setup"));

        MvcResult login = mvc.perform(formLogin("/_/login").user("admin").password(PASSWORD))
                .andExpect(redirectedUrl("/_/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        UUID domainId = domains.findByHost("go.example.com").orElseThrow().getId();
        mvc.perform(post("/_/links").session(session).with(csrf())
                        .param("domainId", domainId.toString())
                        .param("slug", "Promo")
                        .param("targetUrl", "https://example.com/landing")
                        .param("enabled", "true")
                        .param("maxUses", "1"))
                .andExpect(redirectedUrl("/_/"));

        mvc.perform(get("http://go.example.com/promo"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/landing"));
        mvc.perform(get("http://go.example.com/promo"))
                .andExpect(status().isGone());

        mvc.perform(get("/_/setup")).andExpect(status().isNotFound());
    }
}
