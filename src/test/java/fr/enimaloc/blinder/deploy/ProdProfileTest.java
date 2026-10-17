package fr.enimaloc.blinder.deploy;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceCookieService;
import fr.enimaloc.blinder.device.DeviceSyncService;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Profil {@code prod} : cookies sécurisés ; le client de test (loopback) est un proxy de confiance par défaut. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import(TestData.class)
class ProdProfileTest {

    @LocalServerPort
    int port;
    @Autowired
    TestData data;
    @Autowired
    DeviceSyncService sync;

    HttpClient client;

    @BeforeEach
    void setUp() {
        data.reset();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String host, String pathAndQuery, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .header("Host", host);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sessionCookieIsSecureLaxAndHttpOnly() throws Exception {
        data.admin("admin");
        HttpResponse<String> response = get("go.example.com", "/_/login");
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("JSESSIONID=")
                .contains("Secure")
                .contains("HttpOnly")
                .containsIgnoringCase("SameSite=Lax"));
    }

    @Test
    void deviceCookieIsSecureBehindTrustedProxyForwardingHttps() throws Exception {
        User owner = data.admin("admin");
        Domain a = data.domain("a.example.com", owner);
        data.domain("b.example.com", owner);
        Device device = data.device(owner, "Laptop");
        data.credential(device, a);
        String hop = sync.begin(device.getId(), a.getId(), "https", 443).orElseThrow();
        String pathAndQuery = hop.substring(hop.indexOf("/_/device/sync"));

        HttpResponse<String> response = get("b.example.com", pathAndQuery, "X-Forwarded-Proto", "https");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .startsWith(DeviceCookieService.COOKIE + "=")
                .contains("Secure")
                .contains("HttpOnly"));
    }
}
