package fr.enimaloc.blinder;

import fr.enimaloc.blinder.device.DeviceCookieService;
import fr.enimaloc.blinder.device.DeviceRepository;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.setup.SetupGuard;
import fr.enimaloc.blinder.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LotTwoEndToEndTest {

    static final String ADMIN_PASSWORD = "an-admin-passphrase";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    SetupGuard guard;
    @Autowired
    DomainRepository domains;
    @Autowired
    DeviceRepository devices;

    static String deviceCookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(DeviceCookieService.COOKIE + "="))
                .map(h -> h.substring(h.indexOf('=') + 1, h.indexOf(';')))
                .findFirst().orElseThrow();
    }

    @Test
    void enrolDeviceAcrossDomainsThenOpenProtectedLink() throws Exception {
        data.reset();
        guard.refresh();
        mvc.perform(post("http://go.example.com/_/setup").with(csrf())
                        .param("token", guard.currentToken().orElseThrow())
                        .param("username", "admin")
                        .param("password", ADMIN_PASSWORD)
                        .param("passwordConfirm", ADMIN_PASSWORD)
                        .param("host", "go.example.com"))
                .andExpect(redirectedUrl("/_/login?setup"));
        MockHttpSession admin = (MockHttpSession) mvc.perform(formLogin("/_/login").user("admin").password(ADMIN_PASSWORD))
                .andExpect(redirectedUrl("/_/"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(post("/_/domains").session(admin).with(csrf()).param("host", "vault.example.com")
                        .param("enabled", "true"))
                .andExpect(redirectedUrl("/_/domains"));
        UUID go = domains.findByHost("go.example.com").orElseThrow().getId();
        UUID vaultDomain = domains.findByHost("vault.example.com").orElseThrow().getId();

        String created = mvc.perform(post("/_/devices").session(admin).with(csrf())
                        .param("name", "Laptop").param("startDomainId", go.toString()))
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("/_/enroll/([A-Za-z0-9_-]+)").matcher(created);
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);

        mvc.perform(get("http://go.example.com/_/enroll/" + token)).andExpect(status().isOk());
        MvcResult enrolled = mvc.perform(post("http://go.example.com/_/enroll/" + token).with(csrf())).andReturn();
        String hop = enrolled.getResponse().getRedirectedUrl();
        assertThat(hop).startsWith("http://vault.example.com/_/device/sync");
        MvcResult synced = mvc.perform(get(URI.create(hop))).andExpect(status().isOk()).andReturn();
        String vaultCookie = deviceCookie(synced);
        UUID laptop = devices.findAll().getFirst().getId();

        mvc.perform(post("/_/links").session(admin).with(csrf())
                        .param("domainId", vaultDomain.toString())
                        .param("slug", "plans")
                        .param("targetUrl", "https://example.com/plans")
                        .param("enabled", "true")
                        .param("newPassword", "open-sesame")
                        .param("deviceIds", laptop.toString()))
                .andExpect(redirectedUrl("/_/"));

        mvc.perform(get("http://vault.example.com/plans"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("example.com/plans"))));
        mvc.perform(get("http://vault.example.com/plans").cookie(new Cookie(DeviceCookieService.COOKIE, vaultCookie)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/plans"));
        mvc.perform(get("http://vault.example.com/plans").param("pw", "open-sesame"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/plans"));
    }
}
