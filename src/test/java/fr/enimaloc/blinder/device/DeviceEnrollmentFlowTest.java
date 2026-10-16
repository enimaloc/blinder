package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class DeviceEnrollmentFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    DeviceRepository devices;
    @Autowired
    DeviceCredentialRepository credentials;
    @Autowired
    DeviceEnrollmentRepository enrollments;
    @Autowired
    DomainRepository domains;

    User owner;
    Domain a;
    Domain b;
    Domain foreign;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        a = data.domain("a.example.com", owner);
        b = data.domain("b.example.com", owner);
        foreign = data.domain("c.example.com", data.admin("other"));
    }

    String token() {
        return data.enrollment(owner, a, "Laptop", Instant.now().plusSeconds(600));
    }

    static String deviceCookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(DeviceCookieService.COOKIE + "="))
                .map(h -> h.substring(h.indexOf('=') + 1, h.indexOf(';')))
                .findFirst().orElseThrow();
    }

    MvcResult enroll(String token) throws Exception {
        return mvc.perform(post("http://a.example.com/_/enroll/" + token).with(csrf())).andReturn();
    }

    @Test
    void getShowsConfirmationWithoutConsuming() throws Exception {
        String token = token();
        mvc.perform(get("http://a.example.com/_/enroll/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(containsString("Laptop")));
        mvc.perform(get("http://a.example.com/_/enroll/" + token)).andExpect(status().isOk());
        assertThat(enrollments.findByTokenHash(DeviceTokens.sha256(token)).orElseThrow().getUsedAt()).isNull();
        assertThat(devices.count()).isZero();
    }

    @Test
    void wrongHostUnknownOrExpiredTokensLookNotFound() throws Exception {
        mvc.perform(get("http://b.example.com/_/enroll/" + token())).andExpect(status().isNotFound());
        mvc.perform(get("http://a.example.com/_/enroll/unknown")).andExpect(status().isNotFound());
        String expired = data.enrollment(owner, a, "Old", Instant.now().minusSeconds(1));
        mvc.perform(get("http://a.example.com/_/enroll/" + expired)).andExpect(status().isNotFound());
        mvc.perform(post("http://a.example.com/_/enroll/" + expired).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void enrollmentRequiresCsrf() throws Exception {
        mvc.perform(post("http://a.example.com/_/enroll/" + token())).andExpect(status().isForbidden());
        assertThat(devices.count()).isZero();
    }

    @Test
    void enrollingSetsCookieAndChainsThroughEveryActiveDomain() throws Exception {
        String token = token();
        MvcResult first = enroll(token);
        assertThat(first.getResponse().getStatus()).isEqualTo(302);
        String hop = first.getResponse().getRedirectedUrl();
        assertThat(hop).startsWith("http://b.example.com/_/device/sync?d=");
        assertThat(first.getResponse().getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h)
                .startsWith(DeviceCookieService.COOKIE + "=").contains("HttpOnly").contains("SameSite=Lax"));

        Device device = devices.findAll().getFirst();
        assertThat(device.getName()).isEqualTo("Laptop");

        MvcResult second = mvc.perform(get(URI.create(hop))).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString()).contains("2 domaine(s)");
        assertThat(deviceCookie(second)).startsWith(device.getId() + ".");
        assertThat(credentials.countByDeviceId(device.getId())).isEqualTo(2);

        mvc.perform(get(URI.create(hop))).andExpect(status().isNotFound());
        assertThat(enroll(token).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void disabledAndForeignDomainsAreSkipped() throws Exception {
        Domain disabled = domains.findById(b.getId()).orElseThrow();
        disabled.setEnabled(false);
        domains.save(disabled);
        MvcResult result = enroll(token());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("1 domaine(s)");
    }

    @Test
    void expiredTicketIsRejected() throws Exception {
        String hop = enroll(token()).getResponse().getRedirectedUrl();
        String ticket = UriComponentsBuilder.fromUriString(hop).build().getQueryParams().getFirst("t");
        UUID deviceId = devices.findAll().getFirst().getId();
        devices.setTicket(deviceId, DeviceTokens.sha256(ticket), Instant.now().minusSeconds(1));
        mvc.perform(get(URI.create(hop))).andExpect(status().isNotFound());
    }

    @Test
    void hopOnForeignDomainIsRejected() throws Exception {
        String hop = enroll(token()).getResponse().getRedirectedUrl();
        mvc.perform(get(URI.create(hop.replace("b.example.com", "c.example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void resyncRestartsChainFromAnyAuthorisedDomain() throws Exception {
        Device device = data.device(owner, "Phone");
        String cookie = data.credential(device, a);
        MvcResult result = mvc.perform(get("http://a.example.com/_/device/resync")
                        .cookie(new Cookie(DeviceCookieService.COOKIE, cookie)))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("http://b.example.com/_/device/sync?d=" + device.getId());

        mvc.perform(get("http://a.example.com/_/device/resync")).andExpect(status().isNotFound());
    }
}
