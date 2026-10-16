package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.api.token.ApiToken;
import fr.enimaloc.blinder.api.token.ApiTokenRepository;
import fr.enimaloc.blinder.device.DeviceTokens;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.FlashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class ApiTokenAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    ApiTokenRepository tokens;

    User admin;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        asAdmin = user(new BlinderUserDetails(admin));
    }

    @Test
    void createShowsTheRawTokenOnceAndStoresOnlyItsHash() throws Exception {
        MvcResult created = mvc.perform(post("/_/tokens").with(asAdmin).with(csrf()).param("name", "  CI  "))
                .andExpect(redirectedUrl("/_/tokens"))
                .andReturn();
        FlashMap flash = created.getFlashMap();
        String raw = (String) flash.get("createdToken");
        assertThat(raw).startsWith("bl_").hasSize(46);

        ApiToken stored = tokens.findByOwnerOrderByCreatedAtDesc(admin).getFirst();
        assertThat(stored.getName()).isEqualTo("CI");
        assertThat(stored.getTokenHash()).isEqualTo(DeviceTokens.sha256(raw));
        assertThat(stored.getTokenPrefix()).isEqualTo(raw.substring(0, 10));

        mvc.perform(get("/_/tokens").with(asAdmin).flashAttrs(flash))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(raw)))
                .andExpect(content().string(containsString("une seule fois")));
        mvc.perform(get("/_/tokens").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CI")))
                .andExpect(content().string(containsString(raw.substring(0, 10))))
                .andExpect(content().string(not(containsString(raw))));
    }

    @Test
    void nameIsRequiredAndAtMost100Characters() throws Exception {
        mvc.perform(post("/_/tokens").with(asAdmin).with(csrf()).param("name", "   "))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Le nom doit contenir entre 1 et 100 caractères")));
        mvc.perform(post("/_/tokens").with(asAdmin).with(csrf()).param("name", "x".repeat(101)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Le nom doit contenir entre 1 et 100 caractères")));
        assertThat(tokens.findAll()).isEmpty();
        mvc.perform(post("/_/tokens").with(asAdmin).with(csrf()).param("name", "x".repeat(100)))
                .andExpect(redirectedUrl("/_/tokens"));
    }

    @Test
    void revokeDeletesTheToken() throws Exception {
        data.apiToken(admin, "ci");
        ApiToken token = tokens.findAll().getFirst();
        mvc.perform(post("/_/tokens/" + token.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/tokens"));
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    void foreignTokenIs404AndInvisible() throws Exception {
        data.apiToken(data.admin("other"), "theirs");
        ApiToken theirs = tokens.findAll().getFirst();
        mvc.perform(post("/_/tokens/" + theirs.getId() + "/delete").with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(tokens.findById(theirs.getId())).isPresent();
        mvc.perform(get("/_/tokens").with(asAdmin))
                .andExpect(content().string(not(containsString("theirs"))));
    }

    @Test
    void navHasTokensEntryBeforeAccountAndAnonymousIsRedirected() throws Exception {
        String html = mvc.perform(get("/_/tokens").with(asAdmin)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("href=\"/_/tokens\"");
        assertThat(html.indexOf("href=\"/_/tokens\"")).isLessThan(html.indexOf("href=\"/_/account\""));
        assertThat(html).contains("Jetons d");
        mvc.perform(get("/_/tokens")).andExpect(status().is3xxRedirection());
    }
}
