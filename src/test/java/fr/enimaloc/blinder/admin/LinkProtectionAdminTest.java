package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceService;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class LinkProtectionAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DeviceService deviceService;

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

    MockHttpServletRequestBuilder create(String slug) {
        return post("/_/links").with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", slug)
                .param("targetUrl", "https://example.com/" + slug)
                .param("enabled", "true");
    }

    Link link(String slug) {
        return links.findByDomainAndSlug(domain, slug).orElseThrow();
    }

    @Test
    void setsPasswordHashedAndNeverRendersIt() throws Exception {
        mvc.perform(create("vault").param("newPassword", "open-sesame")).andExpect(redirectedUrl("/_/"));
        Link vault = link("vault");
        assertThat(encoder.matches("open-sesame", vault.getPasswordHash())).isTrue();

        mvc.perform(get("/_/links/{id}", vault.getId()).with(asAdmin))
                .andExpect(content().string(not(containsString(vault.getPasswordHash()))))
                .andExpect(content().string(not(containsString("open-sesame"))))
                .andExpect(content().string(containsString("Supprimer le mot de passe")));
        mvc.perform(get("/_/").with(asAdmin)).andExpect(content().string(containsString("Protégé")));
    }

    @Test
    void blankKeepsAndRemoveClearsPassword() throws Exception {
        mvc.perform(create("vault").param("newPassword", "open-sesame"));
        Link vault = link("vault");
        String hash = vault.getPasswordHash();

        mvc.perform(post("/_/links/{id}", vault.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString()).param("slug", "vault")
                        .param("targetUrl", "https://example.com/vault").param("enabled", "true")
                        .param("newPassword", "").param("version", String.valueOf(vault.getVersion())))
                .andExpect(redirectedUrl("/_/"));
        assertThat(link("vault").getPasswordHash()).isEqualTo(hash);

        Link current = link("vault");
        mvc.perform(post("/_/links/{id}", current.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString()).param("slug", "vault")
                        .param("targetUrl", "https://example.com/vault").param("enabled", "true")
                        .param("removePassword", "true").param("version", String.valueOf(current.getVersion())))
                .andExpect(redirectedUrl("/_/"));
        assertThat(link("vault").getPasswordHash()).isNull();
    }

    @Test
    void rejectsTooShortOrTooLongPassword() throws Exception {
        mvc.perform(create("a").param("newPassword", "abc"))
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
        mvc.perform(create("b").param("newPassword", "é".repeat(37)))
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
    }

    @Test
    void normalizesAllowlistAndRejectsHostnamesOrBadCidr() throws Exception {
        mvc.perform(create("office").param("ipAllowlist", "10.0.0.0/8\n2001:db8::/32"))
                .andExpect(redirectedUrl("/_/"));
        assertThat(link("office").getIpAllowlist()).isEqualTo("10.0.0.0/8,2001:db8:0:0:0:0:0:0/32");

        mvc.perform(create("bad1").param("ipAllowlist", "example.com"))
                .andExpect(model().attributeHasFieldErrors("form", "ipAllowlist"));
        mvc.perform(create("bad2").param("ipAllowlist", "10.0.0.0/33"))
                .andExpect(model().attributeHasFieldErrors("form", "ipAllowlist"));
    }

    @Test
    void attachesOwnDevicesOnlyAndShowsThemChecked() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Device foreign = data.device(data.admin("other"), "Foreign");

        mvc.perform(create("private").param("deviceIds", laptop.getId().toString()))
                .andExpect(redirectedUrl("/_/"));
        Link created = link("private");
        assertThat(created.isDeviceProtected()).isTrue();
        assertThat(links.findDeviceIds(created.getId())).containsExactly(laptop.getId());

        mvc.perform(get("/_/links/{id}", created.getId()).with(asAdmin))
                .andExpect(content().string(containsString("Laptop")))
                .andExpect(content().string(containsString("checked")));

        mvc.perform(create("nope").param("deviceIds", foreign.getId().toString()))
                .andExpect(model().attributeHasFieldErrors("form", "deviceIds"));
    }

    @Test
    void warnsWhenEveryAuthorisedDeviceWasRevoked() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Link private1 = data.link(domain, "gone", "https://example.com/gone");
        data.allowDevice(private1, laptop);
        mvc.perform(get("/_/links/{id}", private1.getId()).with(asAdmin))
                .andExpect(content().string(not(containsString("Aucun appareil autorisé restant"))));

        deviceService.revoke(admin, laptop.getId());

        mvc.perform(get("/_/links/{id}", private1.getId()).with(asAdmin))
                .andExpect(content().string(containsString("Aucun appareil autorisé restant")));
    }

    @Test
    void untickingEveryDeviceClearsTheFlag() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        mvc.perform(create("temp").param("deviceIds", laptop.getId().toString())).andExpect(redirectedUrl("/_/"));
        Link created = link("temp");
        mvc.perform(post("/_/links/{id}", created.getId()).with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "temp")
                        .param("targetUrl", "https://example.com/temp")
                        .param("enabled", "true")
                        .param("version", String.valueOf(created.getVersion())))
                .andExpect(redirectedUrl("/_/"));
        assertThat(link("temp").isDeviceProtected()).isFalse();
    }

    MockHttpServletRequestBuilder edit(Link link, String target) {
        return post("/_/links/{id}", link.getId()).with(asAdmin).with(csrf())
                .param("domainId", domain.getId().toString())
                .param("slug", link.getSlug())
                .param("targetUrl", target)
                .param("enabled", "true")
                .param("version", String.valueOf(links.findById(link.getId()).orElseThrow().getVersion()));
    }

    @Test
    void editingARevokedDeviceLinkKeepsItClosed() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Link secret = data.link(domain, "secret", "https://example.com/secret");
        data.allowDevice(secret, laptop);
        deviceService.revoke(admin, laptop.getId());

        mvc.perform(get("/_/links/{id}", secret.getId()).with(asAdmin))
                .andExpect(content().string(containsString("name=\"deviceProtected\"")));
        mvc.perform(edit(secret, "https://example.com/new").param("deviceProtected", "true"))
                .andExpect(redirectedUrl("/_/"));

        assertThat(link("secret").isDeviceProtected()).isTrue();
        mvc.perform(get("http://go.example.com/secret")).andExpect(status().isNotFound());
    }

    @Test
    void explicitlyUntickingTheBoxWithNoDeviceMakesTheLinkPublic() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Link secret = data.link(domain, "open", "https://example.com/open");
        data.allowDevice(secret, laptop);
        deviceService.revoke(admin, laptop.getId());

        mvc.perform(edit(secret, "https://example.com/open").param("_deviceProtected", "on"))
                .andExpect(redirectedUrl("/_/"));

        assertThat(link("open").isDeviceProtected()).isFalse();
        mvc.perform(get("http://go.example.com/open")).andExpect(status().isFound());
    }

    @Test
    void tickedDeviceSetsTheFlagEvenWithoutTheBox() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        mvc.perform(create("dev").param("deviceIds", laptop.getId().toString()))
                .andExpect(redirectedUrl("/_/"));
        assertThat(link("dev").isDeviceProtected()).isTrue();
    }
}
