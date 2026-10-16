package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestData.class)
class DeviceCookieServiceTest {

    @Autowired
    DeviceCookieService cookies;
    @Autowired
    TestData data;
    @Autowired
    DeviceRepository devices;
    @Autowired
    DeviceCredentialRepository credentials;
    @Autowired
    DomainRepository domains;

    User owner;
    Domain a;
    Domain b;
    Device device;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        a = data.domain("a.example.com", owner);
        b = data.domain("b.example.com", owner);
        device = data.device(owner, "Laptop");
    }

    static MockHttpServletRequest request(String host, String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setServerName(host);
        if (cookieValue != null) {
            request.setCookies(new Cookie(DeviceCookieService.COOKIE, cookieValue));
        }
        return request;
    }

    static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith(DeviceCookieService.COOKIE + "=");
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Test
    void issuedCookieAuthenticatesOnItsDomainOnly() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.issue(device, a, request("a.example.com", null), response);
        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/").contains("Max-Age=34560000");
        assertThat(header).doesNotContain("Secure");
        String value = cookieValue(response);

        assertThat(cookies.authenticate(request("a.example.com", value))).contains(device.getId());
        assertThat(cookies.authenticate(request("b.example.com", value))).isEmpty();
        assertThat(devices.findById(device.getId()).orElseThrow().getLastSeenAt()).isNotNull();
    }

    @Test
    void secureRequestsGetSecureCookie() {
        MockHttpServletRequest request = request("a.example.com", null);
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.issue(device, a, request, response);
        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }

    @Test
    void reissueReplacesPreviousSecret() {
        MockHttpServletResponse first = new MockHttpServletResponse();
        cookies.issue(device, a, request("a.example.com", null), first);
        MockHttpServletResponse second = new MockHttpServletResponse();
        cookies.issue(device, a, request("a.example.com", null), second);

        assertThat(cookies.authenticate(request("a.example.com", cookieValue(first)))).isEmpty();
        assertThat(cookies.authenticate(request("a.example.com", cookieValue(second)))).contains(device.getId());
        assertThat(credentials.countByDeviceId(device.getId())).isEqualTo(1);
    }

    @Test
    void disabledDomainAndRevokedDeviceAreRejected() {
        String value = data.credential(device, a);
        assertThat(cookies.authenticate(request("a.example.com", value))).contains(device.getId());

        Domain disabled = domains.findById(a.getId()).orElseThrow();
        disabled.setEnabled(false);
        domains.save(disabled);
        assertThat(cookies.authenticate(request("a.example.com", value))).isEmpty();

        Domain enabled = domains.findById(a.getId()).orElseThrow();
        enabled.setEnabled(true);
        domains.save(enabled);
        devices.deleteById(device.getId());
        assertThat(cookies.authenticate(request("a.example.com", value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", ".", "not-a-uuid.secret", "00000000-0000-0000-0000-000000000000.", "x.y.z"})
    void malformedCookiesAreRejected(String value) {
        assertThat(cookies.authenticate(request("a.example.com", value))).isEmpty();
    }

    @Test
    void tamperedSecretIsRejected() {
        String value = data.credential(device, a);
        assertThat(cookies.authenticate(request("a.example.com", value + "x"))).isEmpty();
        assertThat(cookies.authenticate(request("a.example.com", null))).isEmpty();
    }
}
